package cctvai.service;

import cctvai.detection.Detection;
import cctvai.detection.ObjectDetector;
import cctvai.detection.ObjectTracker;

import org.bytedeco.opencv.global.opencv_videoio;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VideoAnalysisService {

    private final ObjectDetector objectDetector;

    private final VideoDescriptionService videoDescriptionService;

    /*
     * Uploaded videos will be stored here.
     *
     * Project:
     *
     * cctv-ai-java
     * └── data
     *     └── uploaded-videos
     */
    private static final Path UPLOAD_DIR = Paths.get(
            "C:/Users/Lenovo/OneDrive/Desktop/Document/cctv-ai-java/data/uploaded-videos"
    );

    public VideoAnalysisService(
            ObjectDetector objectDetector,
            VideoDescriptionService videoDescriptionService
    ) {
        this.objectDetector = objectDetector;
        this.videoDescriptionService = videoDescriptionService;
    }

    /**
     * ========================================================
     * ANALYSE UPLOADED VIDEO
     * ========================================================
     */
    public Map<String, Object> analyse(
            MultipartFile file
    ) throws IOException {

        /*
         * ========================================================
         * IMPORTANT:
         *
         * This tracker belongs ONLY to this uploaded video.
         *
         * It is NOT the tracker used by the live camera.
         *
         * Therefore:
         *
         * Live camera tracking
         *        !=
         * Uploaded video tracking
         *
         * Objects detected in an uploaded video cannot
         * contaminate the live camera tracker.
         * ========================================================
         */
        ObjectTracker videoObjectTracker =
                new ObjectTracker();

        /*
         * Create upload directory if it does not exist.
         */
        Files.createDirectories(
                UPLOAD_DIR
        );

        /*
         * Get original filename.
         */
        String originalFilename =
                file.getOriginalFilename();

        if (originalFilename == null
                || originalFilename.isBlank()) {

            originalFilename =
                    "uploaded-video.mp4";
        }

        /*
         * Make filename safe.
         */
        String safeFilename =
                System.currentTimeMillis()
                        + "_"
                        + originalFilename.replaceAll(
                        "[^a-zA-Z0-9._-]",
                        "_"
                );

        Path videoPath =
                UPLOAD_DIR.resolve(
                        safeFilename
                );

        /*
         * Save uploaded video.
         *
         * Files.copy() streams the upload to disk.
         */
        Files.copy(
                file.getInputStream(),
                videoPath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
        );

        System.out.println();
        System.out.println(
                "========================================"
        );
        System.out.println(
                "VIDEO ANALYSIS STARTED"
        );
        System.out.println(
                "========================================"
        );

        System.out.println(
                "Video: "
                        + videoPath.toAbsolutePath()
        );

        /*
         * Open video using OpenCV.
         */
        VideoCapture capture =
                new VideoCapture();

        try {

            boolean opened =
                    capture.open(
                            videoPath.toString(),
                            opencv_videoio.CAP_FFMPEG
                    );

            /*
             * If FFmpeg backend fails,
             * try normal OpenCV backend.
             */
            if (!opened
                    || !capture.isOpened()) {

                System.out.println(
                        "FFmpeg backend could not open video."
                );

                System.out.println(
                        "Trying default OpenCV backend..."
                );

                capture.release();

                opened =
                        capture.open(
                                videoPath.toString()
                        );
            }

            /*
             * Video could not be opened.
             */
            if (!opened
                    || !capture.isOpened()) {

                throw new IllegalStateException(
                        "Could not open uploaded video: "
                                + videoPath
                );
            }

            /*
             * Get FPS.
             */
            double fps =
                    capture.get(
                            opencv_videoio.CAP_PROP_FPS
                    );

            /*
             * Some videos do not report FPS correctly.
             */
            if (fps <= 0
                    || Double.isNaN(fps)
                    || Double.isInfinite(fps)) {

                fps = 25.0;
            }

            /*
             * Get total frame count.
             */
            double frameCount =
                    capture.get(
                            opencv_videoio.CAP_PROP_FRAME_COUNT
                    );

            /*
             * Calculate duration.
             */
            double durationSeconds =
                    frameCount / fps;

            System.out.println(
                    "FPS: " + fps
            );

            System.out.println(
                    "Frame count: " + frameCount
            );

            System.out.println(
                    "Duration: "
                            + formatTime(
                            durationSeconds
                    )
            );

            /*
             * ====================================================
             * ACTIVE EVENTS
             * ====================================================
             */
            Map<Integer, ActiveVideoEvent>
                    activeEvents =
                    new LinkedHashMap<>();

            /*
             * Completed events.
             */
            List<Map<String, Object>>
                    completedEvents =
                    new ArrayList<>();

            /*
             * Unique detected object types.
             */
            List<String> detectedTypes =
                    new ArrayList<>();

            /*
             * Frame buffer.
             */
            Mat frame =
                    new Mat();

            long frameNumber = 0;

            /*
             * ====================================================
             * PROCESS VIDEO FRAME BY FRAME
             * ====================================================
             */
            while (capture.read(frame)) {

                frameNumber++;

                /*
                 * Stop if OpenCV gives an empty frame.
                 */
                if (frame.empty()) {
                    break;
                }

                /*
                 * Current video timestamp.
                 */
                double videoTimeSeconds =
                        frameNumber / fps;

                /*
                 * =================================================
                 * YOLO DETECTION
                 * =================================================
                 */
                List<Detection> detections =
                        objectDetector.detect(
                                frame
                        );

                /*
                 * =================================================
                 * OBJECT TRACKING
                 *
                 * IMPORTANT:
                 *
                 * Use the PRIVATE VIDEO tracker.
                 *
                 * DO NOT use the live camera tracker.
                 * =================================================
                 */
                List<ObjectTracker.TrackedObject>
                        trackedObjects =
                        videoObjectTracker.update(
                                detections
                        );

                /*
                 * =================================================
                 * PROCESS CURRENTLY TRACKED OBJECTS
                 * =================================================
                 */
                for (
                        ObjectTracker.TrackedObject object
                        : trackedObjects
                ) {

                    String objectType =
                            object.getLabel();

                    /*
                     * Add object type to unique list.
                     */
                    if (!detectedTypes
                            .contains(objectType)) {

                        detectedTypes.add(
                                objectType
                        );
                    }

                    int objectId =
                            object.getId();

                    /*
                     * =================================================
                     * NEW EVENT
                     * =================================================
                     */
                    if (!activeEvents
                            .containsKey(objectId)) {

                        ActiveVideoEvent event =
                                new ActiveVideoEvent(
                                        objectId,
                                        objectType,
                                        videoTimeSeconds
                                );

                        activeEvents.put(
                                objectId,
                                event
                        );

                        System.out.println(
                                "VIDEO EVENT START: "
                                        + objectType
                                        + " #"
                                        + objectId
                                        + " at "
                                        + formatTime(
                                        videoTimeSeconds
                                )
                        );
                    }
                }

                /*
                 * =================================================
                 * FIND OBJECTS THAT DISAPPEARED
                 * =================================================
                 */
                List<Integer>
                        currentlyTrackedIds =
                        trackedObjects
                                .stream()
                                .map(
                                        ObjectTracker.TrackedObject
                                                ::getId
                                )
                                .toList();

                List<Integer>
                        disappearedIds =
                        new ArrayList<>();

                for (
                        Integer activeId
                        : activeEvents.keySet()
                ) {

                    if (!currentlyTrackedIds
                            .contains(activeId)) {

                        disappearedIds.add(
                                activeId
                        );
                    }
                }

                /*
                 * =================================================
                 * CLOSE DISAPPEARED EVENTS
                 * =================================================
                 */
                for (
                        Integer disappearedId
                        : disappearedIds
                ) {

                    ActiveVideoEvent event =
                            activeEvents.remove(
                                    disappearedId
                            );

                    if (event != null) {

                        event.endTime =
                                videoTimeSeconds;

                        completedEvents.add(
                                event.toMap()
                        );

                        System.out.println(
                                "VIDEO EVENT END: "
                                        + event.objectType
                                        + " #"
                                        + event.objectId
                                        + " at "
                                        + formatTime(
                                        videoTimeSeconds
                                )
                        );
                    }
                }

                /*
                 * Reuse a fresh Mat for the next frame.
                 */
                frame.release();

                frame = new Mat();
            }

            /*
             * CLOSE EVENTS STILL ACTIVE AT VIDEO END
             */
            double finalTime =
                    durationSeconds;

            for (
                    ActiveVideoEvent event
                    : activeEvents.values()
            ) {

                event.endTime =
                        finalTime;

                completedEvents.add(
                        event.toMap()
                );
            }

            /*
             * Release final frame.
             */
            frame.release();

            /*
             * GENERATE ONE OVERALL AI DESCRIPTION
             */
            System.out.println();
            System.out.println(
                    "========================================"
            );
            System.out.println(
                    "GENERATING OVERALL VIDEO DESCRIPTION"
            );
            System.out.println(
                    "========================================"
            );

            String videoDescription;

            try {

                videoDescription =
                        videoDescriptionService.generateDescription(
                                videoPath,
                                durationSeconds,
                                detectedTypes,
                                completedEvents
                        );

            } catch (Exception e) {

            e.printStackTrace();

            videoDescription =
                    "AI description failed: "
                            + e.getClass().getSimpleName()
                            + " - "
                            + e.getMessage();
        }

            /*
             * BUILD JSON RESPONSE
             */
            Map<String, Object> result =
                    new LinkedHashMap<>();

            result.put(
                    "status",
                    "completed"
            );

            result.put(
                    "filename",
                    originalFilename
            );

            result.put(
                    "duration_seconds",
                    round(durationSeconds)
            );

            result.put(
                    "fps",
                    round(fps)
            );

            result.put(
                    "frame_count",
                    frameNumber
            );

            result.put(
                    "detected_objects",
                    detectedTypes
            );

            result.put(
                    "events",
                    completedEvents
            );

            /*
             * Overall AI description.
             */
            result.put(
                    "video_description",
                    videoDescription
            );

            System.out.println();
            System.out.println(
                    "========================================"
            );
            System.out.println(
                    "VIDEO ANALYSIS COMPLETED"
            );
            System.out.println(
                    "========================================"
            );

            return result;

        } finally {

            /*
             * Close OpenCV video.
             */
            if (capture.isOpened()) {

                capture.release();
            }

            capture.close();

            /*
             * Reset ONLY the uploaded-video tracker.
             *
             * This does NOT touch the live camera tracker.
             */
            videoObjectTracker.reset();
        }
    }

    /**
     * ROUND DECIMAL NUMBER
     */
    private double round(
            double value
    ) {

        return Math.round(
                value * 100.0
        ) / 100.0;
    }

    /**
     * FORMAT VIDEO TIME
     */
    private String formatTime(
            double seconds
    ) {

        long totalSeconds =
                (long) seconds;

        long hours =
                totalSeconds / 3600;

        long minutes =
                (totalSeconds % 3600)
                        / 60;

        long secs =
                totalSeconds % 60;

        return String.format(
                "%02d:%02d:%02d",
                hours,
                minutes,
                secs
        );
    }

    /**
     * ACTIVE VIDEO EVENT
     */
    private static class ActiveVideoEvent {

        private final int objectId;

        private final String objectType;

        private final double startTime;

        private double endTime;

        ActiveVideoEvent(
                int objectId,
                String objectType,
                double startTime
        ) {

            this.objectId =
                    objectId;

            this.objectType =
                    objectType;

            this.startTime =
                    startTime;

            this.endTime =
                    startTime;
        }

        /**
         * Convert event to JSON-friendly Map.
         */
        Map<String, Object> toMap() {

            Map<String, Object> map =
                    new LinkedHashMap<>();

            map.put(
                    "object_id",
                    objectId
            );

            map.put(
                    "object_type",
                    objectType
            );

            map.put(
                    "start_seconds",
                    round(startTime)
            );

            map.put(
                    "end_seconds",
                    round(endTime)
            );

            map.put(
                    "start",
                    format(startTime)
            );

            map.put(
                    "end",
                    format(endTime)
            );

            map.put(
                    "duration_seconds",
                    round(
                            Math.max(
                                    0,
                                    endTime - startTime
                            )
                    )
            );

            return map;
        }

        private static double round(
                double value
        ) {

            return Math.round(
                    value * 100.0
            ) / 100.0;
        }

        private static String format(
                double seconds
        ) {

            long totalSeconds =
                    (long) seconds;

            long hours =
                    totalSeconds / 3600;

            long minutes =
                    (totalSeconds % 3600)
                            / 60;

            long secs =
                    totalSeconds % 60;

            return String.format(
                    "%02d:%02d:%02d",
                    hours,
                    minutes,
                    secs
            );
        }
    }
}