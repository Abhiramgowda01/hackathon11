package cctvai.controller;

import cctvai.service.CameraProcessingService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@CrossOrigin(origins = "*")
public class CameraStreamController {

    private final CameraProcessingService cameraProcessingService;

    public CameraStreamController(
            CameraProcessingService cameraProcessingService
    ) {
        this.cameraProcessingService =
                cameraProcessingService;
    }
    @GetMapping(
            value = "/camera/frame",
            produces = MediaType.IMAGE_JPEG_VALUE
    )
    public ResponseEntity<byte[]> getLatestFrame() {

        byte[] frame =
                cameraProcessingService.getLatestJpegFrame();

        if (frame == null || frame.length == 0) {
            return ResponseEntity
                    .noContent()
                    .build();
        }
        return ResponseEntity
                .ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(frame);
    }
}