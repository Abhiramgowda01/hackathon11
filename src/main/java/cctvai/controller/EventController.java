package cctvai.controller;

import cctvai.model.Event;
import cctvai.repository.EventRepository;
import cctvai.service.AnomalyService;
import cctvai.service.VlmService;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class EventController {

    private final EventRepository eventRepository;
    private final VlmService vlmService;
    private final AnomalyService anomalyService;

    public EventController(
            EventRepository eventRepository,
            VlmService vlmService,
            AnomalyService anomalyService
    ) {
        this.eventRepository = eventRepository;
        this.vlmService = vlmService;
        this.anomalyService = anomalyService;
    }

    // ---------------------------------------------------------
    // HOME
    // ---------------------------------------------------------

    // ---------------------------------------------------------
    // HEALTH CHECK
    // ---------------------------------------------------------

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    // ---------------------------------------------------------
    // PROCESS CCTV FRAME
    // ---------------------------------------------------------

    @PostMapping(
            value = "/process_frame",
            consumes = "multipart/form-data"
    )
    public ResponseEntity<?> processFrame(
            @RequestParam("camera_id") String cameraId,
            @RequestParam("file") MultipartFile file
    ) {

        // Validate com.cctvai.camera ID
        if (cameraId == null || cameraId.trim().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "camera_id is required"
            );
        }

        // Validate file
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Empty file"
            );
        }

        // Validate image
        String contentType = file.getContentType();

        if (contentType == null || !contentType.startsWith("image/")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "File must be an image"
            );
        }

        try {

            // Read image
            byte[] imageBytes = file.getBytes();

            // Get AI description
            String description =
                    vlmService.describeFrame(imageBytes, cameraId);

            // Check anomaly
            AnomalyService.Result result =
                    anomalyService.evaluate(cameraId, description);

            // Create cctvai.event
            Event event = new Event(
                    cameraId,
                    description,
                    result.isAnomaly(),
                    result.maxSimilarity()
            );

            // Save cctvai.event
            eventRepository.save(event);

            // Return response
            return ResponseEntity.ok(
                    Map.of(
                            "id", event.getId(),
                            "camera_id", cameraId,
                            "timestamp", event.getTimestamp().toString(),
                            "description", description,
                            "is_anomaly", result.isAnomaly(),
                            "similarity", result.maxSimilarity(),
                            "baseline_size", result.baselineSize()
                    )
            );

        } catch (IOException e) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Could not read file: " + e.getMessage(),
                    e
            );
        }
    }

    // ---------------------------------------------------------
    // GET EVENTS
    // ---------------------------------------------------------

    @GetMapping("/events")
    public List<Event> listEvents(
            @RequestParam(
                    value = "camera_id",
                    required = false
            )
            String cameraId,

            @RequestParam(
                    value = "anomalies_only",
                    defaultValue = "false"
            )
            boolean anomaliesOnly,

            @RequestParam(
                    value = "limit",
                    defaultValue = "50"
            )
            int limit
    ) {

        // Prevent invalid/huge limits
        int safeLimit = Math.min(
                Math.max(1, limit),
                500
        );

        Pageable pageable = PageRequest.of(
                0,
                safeLimit,
                Sort.by(
                        Sort.Direction.DESC,
                        "timestamp"
                )
        );

        // Camera + anomaly filter
        if (cameraId != null && anomaliesOnly) {

            return eventRepository
                    .findByCameraIdAndAnomalyTrueOrderByTimestampDesc(
                            cameraId,
                            pageable
                    );
        }

        // Camera filter
        if (cameraId != null) {

            return eventRepository
                    .findByCameraIdOrderByTimestampDesc(
                            cameraId,
                            pageable
                    );
        }

        // Anomaly filter
        if (anomaliesOnly) {

            return eventRepository
                    .findByAnomalyTrueOrderByTimestampDesc(
                            pageable
                    );
        }

        // All events
        return eventRepository
                .findAllByOrderByTimestampDesc(
                        pageable
                );
    }

    // ---------------------------------------------------------
    // GET CAMERAS
    // ---------------------------------------------------------

    @GetMapping("/cameras")
    public List<String> listCameras() {
        return eventRepository.findDistinctCameraIds();
    }

    // ---------------------------------------------------------
    // RESET CAMERA
    // ---------------------------------------------------------

    @PostMapping("/cameras/{cameraId}/reset")
    public Map<String, String> resetCamera(
            @PathVariable String cameraId
    ) {

        if (cameraId == null || cameraId.trim().isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "cameraId is required"
            );
        }

        anomalyService.reset(cameraId);

        return Map.of(
                "status", "reset",
                "camera_id", cameraId
        );
    }
}