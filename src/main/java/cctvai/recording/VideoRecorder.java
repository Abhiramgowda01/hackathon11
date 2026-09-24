package cctvai.recording;

import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_videoio.VideoWriter;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
public class VideoRecorder {

    private static final double FPS = 20.0;

    private static final DateTimeFormatter FILE_TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH-mm-ss");

    private VideoWriter videoWriter;

    private Path currentVideoPath;

    private int frameWidth;

    private int frameHeight;

    /**
     * Write one processed CCTV frame to the current MP4 file.
     */
    public synchronized void writeFrame(
            Mat frame,
            String cameraId
    ) {

        if (frame == null || frame.empty()) {
            return;
        }

        /*
         * Create the video writer when the first frame arrives.
         */
        if (videoWriter == null || !videoWriter.isOpened()) {

            startNewRecording(
                    frame,
                    cameraId
            );
        }

        /*
         * If the camera resolution changes,
         * recreate the video writer.
         */
        if (videoWriter != null
                && (
                frame.cols() != frameWidth
                        || frame.rows() != frameHeight
        )) {

            stop();

            startNewRecording(
                    frame,
                    cameraId
            );
        }

        /*
         * Write the frame.
         */
        if (videoWriter != null
                && videoWriter.isOpened()) {

            videoWriter.write(frame);
        }
    }

    /**
     * Creates a new MP4 recording.
     *
     * Files are saved under:
     *
     * recordings/
     *     YYYY-MM-DD/
     *         camera-webcam-0_HH-mm-ss.mp4
     */
    private void startNewRecording(
            Mat frame,
            String cameraId
    ) {

        try {

            frameWidth = frame.cols();
            frameHeight = frame.rows();

            /*
             * Create:
             *
             * recordings/2026-09-23/
             */
            Path directory =
                    Paths.get(
                            "recordings",
                            LocalDate.now().toString()
                    );

            Files.createDirectories(directory);

            /*
             * Make the camera ID safe for a filename.
             */
            String safeCameraId =
                    cameraId
                            .replaceAll(
                                    "[^a-zA-Z0-9_-]",
                                    "_"
                            );

            /*
             * Example:
             *
             * camera-webcam-0_22-05-30.mp4
             */
            String filename =
                    "camera-"
                            + safeCameraId
                            + "_"
                            + LocalDateTime.now()
                            .format(FILE_TIME_FORMAT)
                            + ".mp4";

            currentVideoPath =
                    directory.resolve(filename);

            /*
             * MP4V codec.
             */
            int fourcc =
                    VideoWriter.fourcc(
                            (byte) 'm',
                            (byte) 'p',
                            (byte) '4',
                            (byte) 'v'
                    );

            /*
             * Create OpenCV VideoWriter.
             */
            videoWriter =
                    new VideoWriter(
                            currentVideoPath.toString(),
                            fourcc,
                            FPS,
                            new Size(
                                    frameWidth,
                                    frameHeight
                            ),
                            true
                    );

            /*
             * Check whether the video writer opened.
             */
            if (!videoWriter.isOpened()) {

                System.err.println(
                        "Could not open video writer: "
                                + currentVideoPath
                );

                videoWriter.close();

                videoWriter = null;

                return;
            }

            System.out.println(
                    "VIDEO RECORDING STARTED: "
                            + currentVideoPath
                            .toAbsolutePath()
            );

        } catch (Exception e) {

            videoWriter = null;

            System.err.println(
                    "Could not start video recording: "
                            + e.getMessage()
            );
        }
    }

    /**
     * Returns the current recording path.
     */
    public synchronized String getCurrentVideoPath() {

        if (currentVideoPath == null) {
            return null;
        }

        return currentVideoPath.toString();
    }

    /**
     * Stops the current video recording.
     */
    public synchronized void stop() {

        if (videoWriter == null) {
            return;
        }

        try {

            videoWriter.release();

            System.out.println(
                    "VIDEO RECORDING STOPPED: "
                            + currentVideoPath
            );

        } catch (Exception e) {

            System.err.println(
                    "Error closing video writer: "
                            + e.getMessage()
            );

        } finally {

            videoWriter.close();

            videoWriter = null;
        }
    }
}