package cctvai.camera;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;
import org.springframework.stereotype.Component;

@Component
public class CameraSource {

    private final CameraConfig cameraConfig;

    private VideoCapture videoCapture;


    public CameraSource(CameraConfig cameraConfig) {
        this.cameraConfig = cameraConfig;
    }


    @PostConstruct
    public void start() {

        if (!openCamera()) {
            throw new IllegalStateException(
                    "Could not open camera at startup."
            );
        }
    }


    /*
     * Open / re-open the camera source.
     * Called both at startup, on reconnection, and when turned on.
     * Returns true on success.
     */
    public synchronized boolean openCamera() {

        String cameraType = cameraConfig.getCameraType();

        System.out.println("==========================================");
        System.out.println("              CCTV AI CAMERA");
        System.out.println("==========================================");
        System.out.println("Camera type: " + cameraType);

        /*
         * Release existing capture handle if present.
         */
        if (videoCapture != null) {
            try {
                videoCapture.release();
                videoCapture.close();
            } catch (Exception ignored) {
            }
            videoCapture = null;
        }

        videoCapture = new VideoCapture();

        boolean opened;

        switch (cameraType.toUpperCase()) {

            case "A":
                int webcamIndex = cameraConfig.getWebcamIndex();
                System.out.println(
                        "Opening laptop/USB webcam. Index: " + webcamIndex
                );
                opened = videoCapture.open(webcamIndex);
                break;

            case "B":
                String rtspUrl = cameraConfig.getRtspUrl();
                if (rtspUrl == null || rtspUrl.isBlank()) {
                    throw new IllegalStateException(
                            "Camera type B selected, but camera.rtsp-url is empty."
                    );
                }
                System.out.println("Opening RTSP camera: " + rtspUrl);
                opened = videoCapture.open(
                        rtspUrl,
                        org.bytedeco.opencv.global.opencv_videoio.CAP_FFMPEG
                );
                break;

            case "C":
                String videoFile = cameraConfig.getVideoFile();
                if (videoFile == null || videoFile.isBlank()) {
                    throw new IllegalStateException(
                            "Camera type C selected, but camera.video-file is empty."
                    );
                }
                System.out.println("Opening recorded video: " + videoFile);
                opened = videoCapture.open(videoFile);
                break;

            default:
                throw new IllegalArgumentException(
                        "Invalid camera.type: " + cameraType + ". Use A, B or C."
                );
        }

        if (!opened || !videoCapture.isOpened()) {
            System.err.println(
                    "Could not open camera source. Camera type: " + cameraType
            );
            return false;
        }

        System.out.println("Camera opened successfully.");
        System.out.println("==========================================");
        return true;
    }


    /*
     * Read one frame.
     * Returns null if the camera is closed or returned an empty Mat.
     */
    public synchronized Mat readFrame() {

        if (videoCapture == null || !videoCapture.isOpened()) {
            return null;
        }

        Mat frame = new Mat();

        boolean success = videoCapture.read(frame);

        if (!success || frame.empty()) {
            frame.close();
            return null;
        }

        return frame;
    }


    /*
     * Try to reconnect the camera source after losing frames.
     * Called by CameraProcessingService after consecutive empty frames.
     * Returns true if the camera was successfully reopened.
     */
    public boolean reconnect() {

        System.out.println("Attempting camera reconnection...");

        return openCamera();
    }


    public boolean isOpened() {
        return videoCapture != null && videoCapture.isOpened();
    }


    @PreDestroy
    public void stop() {

        if (videoCapture != null) {

            System.out.println("Closing CCTV camera...");

            videoCapture.release();
            videoCapture.close();

            videoCapture = null;

            System.out.println("CCTV camera closed.");
        }
    }
}
