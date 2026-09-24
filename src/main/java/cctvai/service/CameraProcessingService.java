package cctvai.service;

import cctvai.camera.CameraSource;
import cctvai.detection.Detection;
import cctvai.detection.ObjectDetector;
import cctvai.detection.ObjectTracker;
import cctvai.detection.ObjectTracker.TrackedObject;
import cctvai.model.Event;
import cctvai.recording.VideoRecorder;
import cctvai.repository.EventRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Point;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Scalar;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class CameraProcessingService {

    private static final String CAMERA_ID = "webcam-0";

    private final CameraSource cameraSource;
    private final ObjectDetector objectDetector;
    private final ObjectTracker objectTracker;
    private final EventRepository eventRepository;
    private final VideoRecorder videoRecorder;

    private final AtomicBoolean running =
            new AtomicBoolean(false);

    /*
     * True only when the camera has successfully
     * produced a recent frame.
     */
    private volatile boolean cameraOnline = false;

    /*
     * Latest processed JPEG frame.
     *
     * Set to null when camera becomes offline so
     * the browser cannot continue showing an old frame.
     */
    private volatile byte[] latestJpegFrame;

    private Thread processingThread;

    /*
     * Currently active events.
     *
     * Key   = ObjectTracker ID
     * Value = database Event
     */
    private final Map<Integer, Event> activeEvents =
            new HashMap<>();

    public CameraProcessingService(
            CameraSource cameraSource,
            ObjectDetector objectDetector,
            ObjectTracker objectTracker,
            EventRepository eventRepository,
            VideoRecorder videoRecorder
    ) {
        this.cameraSource = cameraSource;
        this.objectDetector = objectDetector;
        this.objectTracker = objectTracker;
        this.eventRepository = eventRepository;
        this.videoRecorder = videoRecorder;
    }

    @PostConstruct
    public void start() {

        if (running.get()) {
            return;
        }

        running.set(true);
        cameraOnline = false;
        latestJpegFrame = null;

        processingThread =
                new Thread(
                        this::processCamera,
                        "cctv-camera-processing"
                );

        processingThread.setDaemon(true);

        processingThread.start();

        System.out.println(
                "CCTV camera processing started."
        );
    }

    private void processCamera() {

        while (running.get()) {

            Mat frame = null;

            try {

                /*
                 * Read frame from camera.
                 */
                frame =
                        cameraSource.readFrame();

                /*
                 * Camera did not return a frame.
                 */
                if (frame == null || frame.empty()) {

                    handleCameraOffline();

                    sleep(200);

                    continue;
                }

                /*
                 * Camera successfully returned a frame.
                 */
                handleCameraOnline();

                /*
                 * YOLO detection.
                 */
                List<Detection> detections =
                        objectDetector.detect(frame);

                /*
                 * LIVE CAMERA TRACKER ONLY.
                 *
                 * This tracker is separate from the tracker
                 * used by uploaded video analysis.
                 */
                List<TrackedObject> trackedObjects =
                        objectTracker.update(
                                detections
                        );

                /*
                 * Create/maintain/end live camera events.
                 */
                updateTrackingEvents(
                        trackedObjects
                );

                /*
                 * Draw bounding boxes BEFORE recording.
                 *
                 * Therefore the saved MP4 also contains
                 * the bounding boxes and tracking IDs.
                 */
                drawTrackedObjects(
                        frame,
                        trackedObjects
                );

                /*
                 * Record processed frame.
                 */
                videoRecorder.writeFrame(
                        frame,
                        CAMERA_ID
                );

                /*
                 * Console information.
                 */
                for (TrackedObject object :
                        trackedObjects) {

                    System.out.println(
                            "Detected: "
                                    + object.getLabel()
                                    + " #"
                                    + object.getId()
                                    + " confidence="
                                    + String.format(
                                    "%.2f",
                                    object.getConfidence()
                            )
                    );
                }

                /*
                 * Send processed frame to browser.
                 */
                updateLatestJpegFrame(frame);

            } catch (Exception e) {

                System.err.println(
                        "Camera processing error: "
                                + e.getMessage()
                );

                /*
                 * Any camera processing exception is treated
                 * as camera offline.
                 */
                handleCameraOffline();

                sleep(1000);

            } finally {

                if (frame != null) {
                    frame.close();
                }
            }
        }
    }

    /**
     * Called whenever a valid camera frame is received.
     */
    private void handleCameraOnline() {

        if (!cameraOnline) {

            cameraOnline = true;

            System.out.println(
                    "CAMERA ONLINE"
            );
        }
    }

    /**
     * Called when camera frame reading fails.
     *
     * Important:
     * - clears the old JPEG
     * - closes active events
     * - resets live tracker
     * - prevents old objects from appearing as live objects
     */
    private void handleCameraOffline() {

        if (cameraOnline) {

            System.out.println(
                    "CAMERA OFFLINE"
            );
        }

        cameraOnline = false;

        /*
         * Very important:
         * remove the last successful frame.
         */
        latestJpegFrame = null;

        /*
         * Close any live events because the camera
         * is no longer providing frames.
         */
        closeActiveEvents();

        /*
         * Reset the LIVE tracker.
         *
         * This ensures objects from before the camera outage
         * cannot continue after the camera comes back.
         */
        objectTracker.reset();
    }

    /**
     * Handles the complete event lifecycle.
     *
     * New tracker ID
     *      -> event START
     *
     * Tracker ID remains alive
     *      -> event remains active
     *
     * Tracker ID disappears completely
     *      -> event END
     */
    private void updateTrackingEvents(
            List<TrackedObject> trackedObjects
    ) {

        /*
         * IDs currently existing in ObjectTracker.
         *
         * ObjectTracker keeps temporarily missed objects
         * alive, so an event does not immediately end
         * after one missed detection.
         */
        Set<Integer> currentTrackedIds =
                new HashSet<>();

        for (TrackedObject object :
                trackedObjects) {

            int objectId =
                    object.getId();

            currentTrackedIds.add(
                    objectId
            );

            /*
             * New tracked object.
             */
            if (!activeEvents.containsKey(objectId)) {

                Instant startTime =
                        Instant.now();

                String description =
                        object.getLabel()
                                + " #"
                                + objectId
                                + " detected";

                Event event =
                        new Event(
                                CAMERA_ID,
                                objectId,
                                object.getLabel(),
                                startTime,
                                description
                        );

                /*
                 * Save immediately.
                 *
                 * endTime remains NULL while
                 * the object is active.
                 */
                eventRepository.save(event);

                activeEvents.put(
                        objectId,
                        event
                );

                System.out.println(
                        "EVENT START: "
                                + description
                                + " at "
                                + startTime
                );
            }
        }

        /*
         * Copy active IDs because we may remove
         * entries from activeEvents below.
         */
        Set<Integer> activeIds =
                new HashSet<>(
                        activeEvents.keySet()
                );

        for (Integer objectId :
                activeIds) {

            /*
             * Object no longer exists in ObjectTracker.
             */
            if (!currentTrackedIds.contains(
                    objectId
            )) {

                Event event =
                        activeEvents.get(
                                objectId
                        );

                if (event != null) {

                    Instant endTime =
                            Instant.now();

                    event.setEndTime(
                            endTime
                    );

                    eventRepository.save(
                            event
                    );

                    System.out.println(
                            "EVENT END: "
                                    + event.getDescription()
                                    + " at "
                                    + endTime
                    );
                }

                activeEvents.remove(
                        objectId
                );
            }
        }
    }

    /**
     * Converts the processed OpenCV frame to JPEG
     * for the browser.
     */
    private void updateLatestJpegFrame(
            Mat frame
    ) {

        BytePointer encoded =
                new BytePointer();

        try {

            boolean success =
                    opencv_imgcodecs.imencode(
                            ".jpg",
                            frame,
                            encoded
                    );

            if (!success) {
                return;
            }

            int size =
                    (int) encoded.limit();

            if (size <= 0) {
                return;
            }

            byte[] jpegBytes =
                    new byte[size];

            encoded.get(
                    jpegBytes
            );

            latestJpegFrame =
                    jpegBytes;

        } finally {

            encoded.close();
        }
    }

    /**
     * Returns the latest processed JPEG.
     *
     * Returns null when camera is offline.
     */
    public byte[] getLatestJpegFrame() {
        return latestJpegFrame;
    }

    /**
     * Returns whether the camera is currently online.
     */
    public boolean isCameraOnline() {
        return cameraOnline;
    }

    /**
     * Draw all currently tracked objects.
     */
    private void drawTrackedObjects(
            Mat frame,
            List<TrackedObject> objects
    ) {

        for (TrackedObject object :
                objects) {

            Rect box =
                    object.getBoundingBox();

            /*
             * Bounding box.
             */
            opencv_imgproc.rectangle(
                    frame,
                    box,
                    new Scalar(
                            0,
                            255,
                            0,
                            0
                    ),
                    2,
                    opencv_imgproc.LINE_8,
                    0
            );

            /*
             * Label.
             *
             * Example:
             *
             * person #4 87%
             */
            String label =
                    object.getLabel()
                            + " #"
                            + object.getId()
                            + " "
                            + String.format(
                            "%.0f%%",
                            object.getConfidence()
                                    * 100
                    );

            Point textPosition =
                    new Point(
                            box.x(),
                            Math.max(
                                    20,
                                    box.y() - 8
                            )
                    );

            opencv_imgproc.putText(
                    frame,
                    label,
                    textPosition,
                    opencv_imgproc
                            .FONT_HERSHEY_SIMPLEX,
                    0.6,
                    new Scalar(
                            0,
                            255,
                            0,
                            0
                    ),
                    2,
                    opencv_imgproc.LINE_8,
                    false
            );
        }
    }

    private void sleep(
            long milliseconds
    ) {

        try {

            Thread.sleep(
                    milliseconds
            );

        } catch (InterruptedException e) {

            Thread.currentThread()
                    .interrupt();

            running.set(false);
        }
    }

    @PreDestroy
    public void stop() {

        running.set(false);

        cameraOnline = false;

        /*
         * Remove old frame so the browser cannot
         * display a stale image after shutdown.
         */
        latestJpegFrame = null;

        /*
         * Close active database events.
         */
        closeActiveEvents();

        /*
         * Reset live tracker.
         */
        objectTracker.reset();

        /*
         * Close MP4 recording.
         */
        videoRecorder.stop();

        /*
         * Wait for processing thread.
         */
        if (processingThread != null) {

            try {

                processingThread.join(
                        2000
                );

            } catch (InterruptedException e) {

                Thread.currentThread()
                        .interrupt();
            }
        }

        System.out.println(
                "CCTV camera processing stopped."
        );
    }

    /**
     * Close events that are still active.
     */
    private void closeActiveEvents() {

        if (activeEvents.isEmpty()) {
            return;
        }

        Instant endTime =
                Instant.now();

        for (Event event :
                activeEvents.values()) {

            if (event.getEndTime() == null) {

                event.setEndTime(
                        endTime
                );

                eventRepository.save(
                        event
                );
            }
        }

        activeEvents.clear();
    }
}