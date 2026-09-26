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

        String cameraType = cameraConfig.getCameraType();

        System.out.println("==========================================");
        System.out.println("              CCTV AI CAMERA");
        System.out.println("==========================================");
        System.out.println("Camera type: " + cameraType);

        videoCapture = new VideoCapture();

        boolean opened;

        switch (cameraType.toUpperCase()) {
            case "A":
                int webcamIndex = cameraConfig.getWebcamIndex();
                System.out.println(
                        "Opening laptop/USB webcam. Index: "
                                + webcamIndex
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

                System.out.println("Opening RTSP camera...");
                System.out.println("RTSP URL: " + rtspUrl);
                System.out.println("Using FFmpeg backend for RTSP...");

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

                System.out.println("Opening recorded CCTV video...");
                System.out.println("Video file: " + videoFile);

                opened = videoCapture.open(videoFile);

                break;

            default:

                throw new IllegalArgumentException(
                        "Invalid camera.type: "
                                + cameraType
                                + ". Use A, B or C."
                );
        }

        if (!opened || !videoCapture.isOpened()) {

            throw new IllegalStateException(
                    "Could not open camera source. "
                            + "Camera type: "
                            + cameraType
            );
        }

        System.out.println("Camera opened successfully.");
        System.out.println("==========================================");
    }

    public synchronized Mat readFrame() {

        if (videoCapture == null || !videoCapture.isOpened()) {

            throw new IllegalStateException(
                    "Camera source is not open."
            );
        }

        Mat frame = new Mat();

        boolean success = videoCapture.read(frame);

        if (!success || frame.empty()) {

            frame.close();

            return null;
        }

        return frame;
    }


    public boolean isOpened() {

        return videoCapture != null
                && videoCapture.isOpened();
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



