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
    private static final Path UPLOAD_DIR = Paths.get("data", "uploaded-videos");

    private final java.util.concurrent.atomic.AtomicBoolean isAnalyzing =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private volatile String progressStatus = "idle";
    private volatile int progressPercent = 0;
    private volatile long progressFrame = 0;
    private volatile long progressTotalFrames = 0;
    private volatile double progressCurrentTimeSeconds = 0;
    private volatile double progressDurationSeconds = 0;
    private volatile int progressEventsCount = 0;
    private volatile String progressMessage = "Waiting for video.";
    private final List<String> progressDetectedObjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public VideoAnalysisService(
            ObjectDetector objectDetector,
            VideoDescriptionService videoDescriptionService
    ) {
        this.objectDetector = objectDetector;
        this.videoDescriptionService = videoDescriptionService;
    }

    public Map<String, Object> getProgress() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", progressStatus);
        map.put("percent", progressPercent);
        map.put("currentFrame", progressFrame);
        map.put("totalFrames", progressTotalFrames);
        map.put("currentTime", formatTime(progressCurrentTimeSeconds));
        map.put("totalTime", formatTime(progressDurationSeconds));
        map.put("eventsCount", progressEventsCount);
        map.put("detectedObjects", new ArrayList<>(progressDetectedObjects));
        map.put("message", progressMessage);
        return map;
    }

    /**
     * ========================================================
     * ANALYSE UPLOADED VIDEO
     * ========================================================
     */
    public Map<String, Object> analyse(
            MultipartFile file
    ) throws IOException {

        if (!isAnalyzing.compareAndSet(false, true)) {
            throw new IllegalStateException("Another video analysis is currently in progress. Please wait for it to complete.");
        }

        progressStatus = "uploading";
        progressPercent = 5;
        progressFrame = 0;
        progressTotalFrames = 0;
        progressCurrentTimeSeconds = 0;
        progressDurationSeconds = 0;
        progressEventsCount = 0;
        progressDetectedObjects.clear();
        progressMessage = "Uploading video file to server...";

        ObjectTracker videoObjectTracker =
                new ObjectTracker();

        VideoCapture capture =
                new VideoCapture();

        try {
            Files.createDirectories(
                    UPLOAD_DIR
            );

            String originalFilename =
                    file.getOriginalFilename();

            if (originalFilename == null
                    || originalFilename.isBlank()) {

                originalFilename =
                        "uploaded-video.mp4";
            }

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

            String videoPathStr = videoPath.toAbsolutePath().normalize().toString();

            boolean opened =
                    capture.open(
                            videoPathStr,
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
                                videoPathStr
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
                    (frameCount > 0 && !Double.isNaN(frameCount) && !Double.isInfinite(frameCount))
                            ? frameCount / fps
                            : 0.0;

            System.out.println(
                    "Frame count: " + frameCount
            );

            System.out.println(
                    "Duration: "
                            + formatTime(
                            durationSeconds
                    )
            );

            progressStatus = "analyzing";
            progressTotalFrames = (long) frameCount;
            progressDurationSeconds = durationSeconds;
            progressPercent = 10;
            progressMessage = "Scanning video frames with AI detection...";

            /*
             * Target ~4 to 5 detections per second.
             * This provides 5x faster processing while capturing every movement and tracked object!
             */
            int frameStep = Math.max(1, (int) Math.round(fps / 4.0));
            System.out.println("Processing video at 1 sample every " + frameStep + " frames (sample rate ~4 fps)...");

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
                 * YOLO DETECTION (Sampled)
                 * =================================================
                 */
                if (frameNumber % frameStep == 0 || frameNumber == 1) {
                    List<Detection> detections =
                            objectDetector.detect(
                                    frame
                            );

                    /*
                     * OBJECT TRACKING
                     */
                    List<ObjectTracker.TrackedObject>
                            trackedObjects =
                            videoObjectTracker.update(
                                    detections
                            );

                    /*
                     * PROCESS CURRENTLY TRACKED OBJECTS
                     */
                    for (
                            ObjectTracker.TrackedObject object
                            : trackedObjects
                    ) {

                        String objectType =
                                object.getLabel();

                        if (!detectedTypes.contains(objectType)) {
                            detectedTypes.add(objectType);
                            if (!progressDetectedObjects.contains(objectType)) {
                                progressDetectedObjects.add(objectType);
                            }
                        }

                        int objectId =
                                object.getId();

                        if (!activeEvents.containsKey(objectId)) {
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
                     * FIND DISAPPEARED OBJECTS
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
                }

                // Update real-time progress
                progressFrame = frameNumber;
                progressCurrentTimeSeconds = videoTimeSeconds;
                progressEventsCount = activeEvents.size() + completedEvents.size();
                if (frameCount > 0) {
                    progressPercent = (int) Math.min(92, Math.round(10 + (frameNumber / frameCount) * 82.0));
                } else {
                    progressPercent = 50;
                }
                progressMessage = String.format(
                        "Analyzing frame %d of %d (%.0f%%) • %d event(s) detected",
                        frameNumber,
                        (long) frameCount,
                        (double) progressPercent,
                        progressEventsCount
                );

                /*
                 * Reuse a fresh Mat for the next frame.
                 */
                frame.release();

                frame = new Mat();
            }

            /*
             * CLOSE EVENTS STILL ACTIVE AT VIDEO END
             */
            if (durationSeconds <= 0 || Double.isNaN(durationSeconds) || Double.isInfinite(durationSeconds)) {
                durationSeconds = frameNumber / fps;
            }

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
            progressStatus = "generating_description";
            progressPercent = 95;
            progressMessage = "Generating AI description and event breakdown...";

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

            progressStatus = "completed";
            progressPercent = 100;
            progressMessage = "Video analysis completed successfully!";

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

        } catch (Exception ex) {
            progressStatus = "failed";
            progressMessage = "Analysis failed: " + ex.getMessage();
            throw ex;
        } finally {

            isAnalyzing.set(false);

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