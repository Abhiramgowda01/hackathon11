package cctvai.controller;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

@RestController
public class RecordingController {

    private static final Path RECORDINGS_ROOT =
            Paths.get("recordings")
                    .toAbsolutePath()
                    .normalize();

    private static final Path UPLOADED_ROOT =
            Paths.get("data", "uploaded-videos")
                    .toAbsolutePath()
                    .normalize();

    /*
     * Search recordings for a date.
     *
     * Example:
     *
     * GET /recordings?date=2026-09-26
     */
    /*
     * List ALL recordings across all date folders.
     *
     * GET /recordings/all
     */
    @GetMapping("/recordings/all")
    public List<Map<String, Object>> listAllRecordings() {

        if (!Files.isDirectory(RECORDINGS_ROOT)) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();

        try (Stream<Path> dateDirs = Files.list(RECORDINGS_ROOT)) {

            dateDirs
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing(
                            Path::getFileName,
                            Comparator.comparing(Path::toString).reversed()
                    ))
                    .forEach(dateDir -> {

                        String dateLabel = dateDir.getFileName().toString();

                        try (Stream<Path> files = Files.list(dateDir)) {

                            files
                                    .filter(Files::isRegularFile)
                                    .filter(p -> p.getFileName().toString()
                                            .toLowerCase().endsWith(".mp4"))
                                    .sorted(Comparator.comparing(
                                            p -> p.getFileName().toString()
                                    ))
                                    .forEach(file -> {

                                        long size = 0L;
                                        try { size = Files.size(file); } catch (IOException ignored) {}

                                        result.add(Map.<String, Object>of(
                                                "date", dateLabel,
                                                "filename", file.getFileName().toString(),
                                                "size", size,
                                                "url", "/recordings/"
                                                        + dateLabel + "/"
                                                        + file.getFileName()
                                        ));
                                    });

                        } catch (IOException ignored) {}
                    });

        } catch (IOException e) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not list recordings.",
                    e
            );
        }

        // Also include analyzed uploaded video recordings
        if (Files.isDirectory(UPLOADED_ROOT)) {
            try (Stream<Path> upFiles = Files.list(UPLOADED_ROOT)) {
                upFiles.filter(Files::isRegularFile)
                        .filter(p -> {
                            String name = p.getFileName().toString().toLowerCase();
                            return name.endsWith(".mp4") || name.endsWith(".avi") || name.endsWith(".mov") || name.endsWith(".mkv");
                        })
                        .forEach(file -> {
                            long size = 0L;
                            try { size = Files.size(file); } catch (IOException ignored) {}
                            result.add(Map.of(
                                    "date", "uploaded",
                                    "filename", file.getFileName().toString(),
                                    "size", size,
                                    "url", "/recordings/uploaded/" + file.getFileName()
                            ));
                        });
            } catch (IOException ignored) {}
        }

        return result;
    }


    /**
     * Find best matching surveillance video recording for an event by timestamp & cameraId.
     * GET /recordings/match?timestamp=2026-09-27T00:53:32.006274Z&cameraId=webcam-0
     */
    @GetMapping("/recordings/match")
    public ResponseEntity<Map<String, Object>> matchRecording(
            @RequestParam(required = false) String timestamp,
            @RequestParam(required = false) String cameraId
    ) {
        if (!Files.isDirectory(RECORDINGS_ROOT)) {
            return ResponseEntity.ok(Map.of("found", false, "message", "No recordings directory exists."));
        }

        // Check if cameraId directly matches an uploaded video file
        if (cameraId != null && !cameraId.isBlank()) {
            String safeName = Paths.get(cameraId).getFileName().toString();
            Path directUploaded = UPLOADED_ROOT.resolve(safeName).normalize();
            if (directUploaded.startsWith(UPLOADED_ROOT) && Files.isRegularFile(directUploaded)) {
                return ResponseEntity.ok(Map.of(
                        "found", true,
                        "url", "/recordings/uploaded/" + safeName,
                        "filename", safeName,
                        "date", "uploaded",
                        "offsetSeconds", 0.0
                ));
            }
        }

        Instant eventInstant = null;
        if (timestamp != null && !timestamp.isBlank()) {
            try {
                eventInstant = Instant.parse(timestamp.trim());
            } catch (Exception e1) {
                try {
                    long millis = Long.parseLong(timestamp.trim());
                    eventInstant = Instant.ofEpochMilli(millis);
                } catch (Exception ignored) {}
            }
        }

        List<Map<String, Object>> allRecs = listAllRecordings();
        if (allRecs.isEmpty()) {
            return ResponseEntity.ok(Map.of("found", false, "message", "No surveillance recordings available."));
        }

        // Prefer recordings with size > 0
        List<Map<String, Object>> validRecs = new ArrayList<>();
        for (Map<String, Object> r : allRecs) {
            Object sizeObj = r.get("size");
            if (sizeObj instanceof Number && ((Number) sizeObj).longValue() > 0) {
                validRecs.add(r);
            }
        }
        if (validRecs.isEmpty()) {
            validRecs = allRecs;
        }

        if (eventInstant == null) {
            Map<String, Object> latest = validRecs.get(validRecs.size() - 1);
            return ResponseEntity.ok(Map.of(
                    "found", true,
                    "url", latest.get("url"),
                    "filename", latest.get("filename"),
                    "date", latest.get("date"),
                    "offsetSeconds", 0
            ));
        }

        long eventEpochSec = eventInstant.getEpochSecond();
        String safeCamId = cameraId != null ? cameraId.replaceAll("[^a-zA-Z0-9_-]", "_") : "";

        Map<String, Object> bestMatch = null;
        double bestOffsetSec = 0.0;
        long minDiffSec = Long.MAX_VALUE;

        for (Map<String, Object> rec : validRecs) {
            String rDate = String.valueOf(rec.get("date"));
            String rFile = String.valueOf(rec.get("filename"));

            try {
                int lastUnderscore = rFile.lastIndexOf('_');
                int dotMp4 = rFile.lastIndexOf(".mp4");
                if (lastUnderscore > 0 && dotMp4 > lastUnderscore) {
                    String timeStr = rFile.substring(lastUnderscore + 1, dotMp4);
                    String[] parts = timeStr.split("-");
                    if (parts.length == 3) {
                        int h = Integer.parseInt(parts[0]);
                        int m = Integer.parseInt(parts[1]);
                        int s = Integer.parseInt(parts[2]);
                        LocalDate recDate = LocalDate.parse(rDate);
                        LocalDateTime recStartLocal = recDate.atTime(h, m, s);
                        Instant recStartInstant = recStartLocal.atZone(ZoneId.systemDefault()).toInstant();
                        long recStartSec = recStartInstant.getEpochSecond();

                        long diff = eventEpochSec - recStartSec;
                        boolean camMatches = safeCamId.isEmpty() || rFile.contains(safeCamId);

                        if (diff >= -5 && diff < 3600) {
                            long score = Math.abs(diff) + (camMatches ? 0 : 5000);
                            if (score < minDiffSec) {
                                minDiffSec = score;
                                bestMatch = rec;
                                bestOffsetSec = Math.max(0, diff);
                            }
                        } else {
                            long absDiff = Math.abs(diff) + (camMatches ? 0 : 100000);
                            if (absDiff < minDiffSec) {
                                minDiffSec = absDiff;
                                bestMatch = rec;
                                bestOffsetSec = Math.max(0, diff);
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        if (bestMatch == null) {
            bestMatch = validRecs.get(validRecs.size() - 1);
        }

        return ResponseEntity.ok(Map.of(
                "found", true,
                "url", bestMatch.get("url"),
                "filename", bestMatch.get("filename"),
                "date", bestMatch.get("date"),
                "offsetSeconds", bestOffsetSec
        ));
    }


    /*
     * Search recordings for a date.
     *
     * Example:
     *
     * GET /recordings?date=2026-09-26
     */
    @GetMapping("/recordings")
    public List<Map<String, Object>> searchRecordings(
            @RequestParam("date") String date
    ) {

        LocalDate localDate = parseDate(date);

        Path directory =
                RECORDINGS_ROOT
                        .resolve(localDate.toString())
                        .normalize();

        if (!directory.startsWith(RECORDINGS_ROOT)
                || !Files.isDirectory(directory)) {

            return List.of();
        }

        try (Stream<Path> files = Files.list(directory)) {

            return files

                    .filter(Files::isRegularFile)

                    .filter(path ->
                            path.getFileName()
                                    .toString()
                                    .toLowerCase()
                                    .endsWith(".mp4")
                    )

                    .sorted(
                            Comparator.comparing(
                                    path -> path.getFileName().toString()
                            )
                    )

                    .map(path -> {

                        try {

                            return Map.<String, Object>of(
                                    "filename",
                                    path.getFileName().toString(),

                                    "size",
                                    Files.size(path),

                                    "url",
                                    "/recordings/"
                                            + localDate
                                            + "/"
                                            + path.getFileName()
                            );

                        } catch (IOException e) {

                            return Map.<String, Object>of(
                                    "filename",
                                    path.getFileName().toString(),

                                    "size",
                                    0L,

                                    "url",
                                    "/recordings/"
                                            + localDate
                                            + "/"
                                            + path.getFileName()
                            );
                        }
                    })

                    .toList();

        } catch (IOException e) {

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not search recordings.",
                    e
            );
        }
    }


    /*
     * Play a recording.
     *
     * Example:
     *
     * GET /recordings/2026-09-26/camera-webcam-0_10-00-00.mp4
     */
    @GetMapping(
            "/recordings/{date}/{filename:.+}"
    )
    public ResponseEntity<Resource> getRecording(
            @PathVariable String date,
            @PathVariable String filename
    ) {

        /*
         * Security:
         * prevent ../ path traversal.
         */
        if (filename.contains("..")
                || filename.contains("/")
                || filename.contains("\\")) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Invalid filename."
            );
        }

        Path file;
        if ("uploaded".equalsIgnoreCase(date)) {
            file = UPLOADED_ROOT.resolve(filename).normalize();
            if (!file.startsWith(UPLOADED_ROOT) || !Files.isRegularFile(file)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Uploaded video not found.");
            }
        } else {
            LocalDate localDate = parseDate(date);
            file = RECORDINGS_ROOT
                    .resolve(localDate.toString())
                    .resolve(filename)
                    .normalize();
            if (!file.startsWith(RECORDINGS_ROOT) || !Files.isRegularFile(file)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recording not found.");
            }
        }

        try {

            Resource resource =
                    new UrlResource(
                            file.toUri()
                    );

            return ResponseEntity.ok()

                    .header(
                            HttpHeaders.CONTENT_DISPOSITION,
                            "inline; filename=\""
                                    + filename
                                    + "\""
                    )

                    .contentType(
                            MediaType.parseMediaType(
                                    "video/mp4"
                            )
                    )

                    .contentLength(
                            Files.size(file)
                    )

                    .body(resource);

        } catch (IOException e) {

            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not open recording.",
                    e
            );
        }
    }


    private LocalDate parseDate(
            String value
    ) {

        try {

            return LocalDate.parse(value);

        } catch (DateTimeParseException e) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Date must use YYYY-MM-DD format."
            );
        }
    }
}