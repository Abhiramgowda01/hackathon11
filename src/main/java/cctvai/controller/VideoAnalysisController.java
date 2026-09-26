package cctvai.controller;

import cctvai.service.VideoAnalysisService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/video")
public class VideoAnalysisController {

    private final VideoAnalysisService videoAnalysisService;


    public VideoAnalysisController(
            VideoAnalysisService videoAnalysisService
    ) {

        this.videoAnalysisService =
                videoAnalysisService;
    }


    @PostMapping(
            value = "/analyse",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<?> analyseVideo(
            @RequestParam("file") MultipartFile file
    ) {
        if (file == null || file.isEmpty()) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    "Please select a video file."
                            )
                    );
        }


        String filename =
                file.getOriginalFilename();


        if (filename == null
                || filename.isBlank()) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    "Invalid video filename."
                            )
                    );
        }

        String lowerFilename =
                filename.toLowerCase();


        boolean supported =
                lowerFilename.endsWith(".mp4")
                        || lowerFilename.endsWith(".avi")
                        || lowerFilename.endsWith(".mov")
                        || lowerFilename.endsWith(".mkv");


        if (!supported) {

            return ResponseEntity
                    .badRequest()
                    .body(
                            Map.of(
                                    "error",
                                    "Supported video formats: MP4, AVI, MOV, MKV"
                            )
                    );
        }

        try {

            Map<String, Object> result =
                    videoAnalysisService.analyse(file);

            return ResponseEntity.ok(
                    result
            );

        } catch (Exception e) {
            e.printStackTrace();

            return ResponseEntity.internalServerError()
                    .body(
                            Map.of(
                                    "error",
                                    "Video analysis failed: "
                                            + e.getMessage()
                            )
                    );
        }
    }
}