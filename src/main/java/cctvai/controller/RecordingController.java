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
import java.time.LocalDate;
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

        return result;
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

        LocalDate localDate =
                parseDate(date);

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

        Path file =
                RECORDINGS_ROOT
                        .resolve(localDate.toString())
                        .resolve(filename)
                        .normalize();

        if (!file.startsWith(RECORDINGS_ROOT)
                || !Files.isRegularFile(file)
                || !filename
                .toLowerCase()
                .endsWith(".mp4")) {

            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Recording not found."
            );
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