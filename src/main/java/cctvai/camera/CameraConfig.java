package cctvai.camera;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CameraConfig {

    @Value("${camera.type:A}")
    private String cameraType;

    @Value("${camera.webcam-index:0}")
    private int webcamIndex;

    @Value("${camera.rtsp-url:}")
    private String rtspUrl;


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