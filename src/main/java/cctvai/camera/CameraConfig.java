package cctvai.camera;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * ============================================================
 * CCTV CAMERA CONFIGURATION
 * ============================================================
 *
 * Supported camera sources:
 *
 * A = Laptop / USB webcam
 * B = IP CCTV camera using RTSP
 * C = Recorded video file
 *
 * ------------------------------------------------------------
 * CURRENTLY ACTIVE:
 *
 * A - Laptop webcam
 *
 * ------------------------------------------------------------
 *
 * LATER:
 *
 * Change camera.type in application.properties:
 *
 * A -> laptop webcam
 * B -> RTSP CCTV
 * C -> MP4/video file
 *
 * ============================================================
 */
@Configuration
public class CameraConfig {

    /**
     * Camera type.
     *
     * A = Laptop webcam       <-- CURRENT
     * B = RTSP CCTV            <-- FUTURE
     * C = Video file           <-- FUTURE
     */
    @Value("${camera.type:A}")
    private String cameraType;


    /**
     * Laptop / USB webcam index.
     *
     * Usually:
     *
     * 0 = default webcam
     * 1 = second camera
     * 2 = third camera
     */
    @Value("${camera.webcam-index:0}")
    private int webcamIndex;


    /**
     * ========================================================
     * OPTION B - RTSP CCTV CAMERA
     * ========================================================
     *
     * KEEP THIS COMMENTED FOR NOW.
     *
     * Example:
     *
     * rtsp://username:password@192.168.1.100:554/stream
     *
     * Later put the real RTSP URL in application.properties.
     */
    @Value("${camera.rtsp-url:}")
    private String rtspUrl;


    /**
     * ========================================================
     * OPTION C - RECORDED VIDEO
     * ========================================================
     *
     * KEEP THIS COMMENTED FOR NOW.
     *
     * Example:
     *
     * C:/Users/Lenovo/Videos/cctv-test.mp4
     */
    @Value("${camera.video-file:}")
    private String videoFile;


    public String getCameraType() {
        return cameraType;
    }

    public int getWebcamIndex() {
        return webcamIndex;
    }

    public String getRtspUrl() {
        return rtspUrl;
    }

    public String getVideoFile() {
        return videoFile;
    }
}