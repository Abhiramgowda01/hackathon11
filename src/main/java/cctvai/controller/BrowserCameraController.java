package cctvai.controller;

import cctvai.service.BrowserCameraService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Handles browser-webcam sessions.
 *
 * Each remote user gets their own sessionId.
 * Frames are sent here from the browser,
 * YOLO runs server-side, and bounding-box JSON
 * is returned so the browser can draw on canvas.
 *
 * Recording is done entirely in the browser via MediaRecorder
 * and saved to the user's own device.
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/browser")
public class BrowserCameraController {

    private final BrowserCameraService browserCameraService;

    public BrowserCameraController(
            BrowserCameraService browserCameraService
    ) {
        this.browserCameraService = browserCameraService;
    }


    /**
     * POST /browser/detect
     *
     * Accepts one JPEG frame from a remote browser webcam.
     * Returns JSON with detected objects + bounding boxes.
     *
     * Body (multipart/form-data):
     *   session_id  – unique per browser tab
     *   frame       – JPEG image
     */
    @PostMapping(
            value = "/detect",
            consumes = "multipart/form-data"
    )
    public ResponseEntity<Map<String, Object>> detect(
            @RequestParam("session_id") String sessionId,
            @RequestParam("frame")      MultipartFile frame
    ) {

        try {

            Map<String, Object> result =
                    browserCameraService.processFrame(
                            sessionId,
                            frame
                    );

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {

            return ResponseEntity
                    .badRequest()
                    .body(Map.of("error", e.getMessage()));

        } catch (Exception e) {

            System.err.println(
                    "Browser detect error [" + sessionId + "]: "
                            + e.getMessage()
            );

            return ResponseEntity
                    .internalServerError()
                    .body(Map.of("error", e.getMessage()));
        }
    }


    /**
     * POST /browser/session/close
     *
     * Called when a remote user closes or leaves the page.
     * Finalises any open tracking events for that session.
     */
    @PostMapping("/session/close")
    public ResponseEntity<Map<String, String>> closeSession(
            @RequestParam("session_id") String sessionId
    ) {

        browserCameraService.closeSession(sessionId);

        return ResponseEntity.ok(
                Map.of(
                        "status",    "closed",
                        "sessionId", sessionId
                )
        );
    }
}
