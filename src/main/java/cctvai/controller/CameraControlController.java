package cctvai.controller;

import cctvai.service.CameraProcessingService;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST controller for toggling the live camera on/off
 * and querying its current status.
 */
@RestController
@RequestMapping("/camera")
public class CameraControlController {

    private final CameraProcessingService cameraProcessingService;

    public CameraControlController(
            CameraProcessingService cameraProcessingService) {
        this.cameraProcessingService = cameraProcessingService;
    }

    /**
     * POST /camera/toggle
     *
     * If the camera is running → stop it.
     * If the camera is stopped → start it.
     *
     * Returns the new state as JSON:
     *
     * { "running": true, "online": true }
     */
    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggle() {

        if (cameraProcessingService.isRunning()) {
            cameraProcessingService.stop();
        } else {
            cameraProcessingService.start();
        }

        return ResponseEntity.ok(
                buildStatusMap());
    }

    /**
     * GET /camera/status
     *
     * Returns the current state as JSON:
     *
     * { "running": true, "online": true }
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {

        return ResponseEntity.ok(
                buildStatusMap());
    }

    private Map<String, Object> buildStatusMap() {

        return Map.of(
                "running", cameraProcessingService.isRunning(),
                "online", cameraProcessingService.isCameraOnline());
    }
}
