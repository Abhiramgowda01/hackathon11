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
import org.bytedeco.opencv.opencv_core.Size;

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


    /*
     * ============================================================
     * SERVICES
     * ============================================================
     */

    private final CameraSource cameraSource;

    private final ObjectDetector objectDetector;

    private final ObjectTracker objectTracker;

    private final EventRepository eventRepository;

    private final VideoRecorder videoRecorder;

    private final LiveActivityService liveActivityService;


    /*
     * ============================================================
     * RUNNING STATE
     * ============================================================
     */

    private final AtomicBoolean running =
            new AtomicBoolean(false);


    /*
     * True when camera is successfully producing frames.
     */

    private volatile boolean cameraOnline = false;


    /*
     * Latest processed JPEG.
     */

    private volatile byte[] latestJpegFrame;


    private Thread processingThread;


    /*
     * Number of consecutive empty frames.
     * After RECONNECT_AFTER_EMPTY_FRAMES consecutive empties
     * the camera is automatically reconnected.
     */

    private int emptyFrameCount = 0;

    private static final int RECONNECT_AFTER_EMPTY_FRAMES = 30;


    /*
     * ============================================================
     * ACTIVE OBJECT EVENTS
     * ============================================================
     *
     * One Event is created when an object first appears.
     *
     * It remains open while the object is tracked.
     *
     * When the object disappears:
     *
     *     1. final activity is obtained
     *     2. description is changed
     *     3. anomaly is updated
     *     4. endTime is saved
     *
     * Therefore one object produces ONE database event.
     */

    private final Map<Integer, Event> activeEvents =
            new HashMap<>();


    /*
     * ============================================================
     * LAST KNOWN FINAL ACTIVITY
     * ============================================================
     *
     * ObjectActivityTracker removes an object from its internal
     * state after it disappears.
     *
     * Therefore we keep the last activity reported by the live
     * activity service here until the normal tracking event ends.
     *
     * Example:
     *
     * person #4
     *     STATIONARY
     *     WALKING
     *     RUNNING
     *
     * lastKnownFinalActivity:
     *
     * 4 -> RUNNING
     */

    private final Map<Integer, String> lastKnownFinalActivity =
            new HashMap<>();


    /*
     * ============================================================
     * CONSTRUCTOR
     * ============================================================
     */

    public CameraProcessingService(
            CameraSource cameraSource,
            ObjectDetector objectDetector,
            ObjectTracker objectTracker,
            EventRepository eventRepository,
            VideoRecorder videoRecorder,
            LiveActivityService liveActivityService
    ) {

        this.cameraSource = cameraSource;

        this.objectDetector = objectDetector;

        this.objectTracker = objectTracker;

        this.eventRepository = eventRepository;

        this.videoRecorder = videoRecorder;

        this.liveActivityService = liveActivityService;
    }


    /*
     * ============================================================
     * START
     * ============================================================
     */

    @PostConstruct
    public synchronized void start() {

        if (running.get()) {
            return;
        }

        if (!cameraSource.isOpened()) {
            cameraSource.openCamera();
        }

        running.set(true);

        cameraOnline = false;

        latestJpegFrame = null;

        emptyFrameCount = 0;


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


    /*
     * ============================================================
     * CAMERA PROCESSING LOOP
     * ============================================================
     */

    private void processCamera() {

        while (running.get()) {

            Mat frame = null;


            try {

                /*
                 * ------------------------------------------------
                 * READ CAMERA FRAME
                 * ------------------------------------------------
                 */

                frame =
                        cameraSource.readFrame();


                /*
                 * Camera did not return a frame.
                 */

                if (
                        frame == null
                                ||
                                frame.empty()
                ) {

                    emptyFrameCount++;

                    handleCameraOffline();


                    /*
                     * After 30 consecutive empty frames (~6 seconds),
                     * try to reopen the camera.
                     */

                    if (emptyFrameCount >= RECONNECT_AFTER_EMPTY_FRAMES) {

                        emptyFrameCount = 0;

                        System.out.println(
                                "Camera has returned no frames for "
                                + RECONNECT_AFTER_EMPTY_FRAMES
                                + " consecutive reads. Reconnecting..."
                        );

                        cameraSource.reconnect();

                        sleep(2000);

                    } else {

                        sleep(200);
                    }

                    continue;
                }


                /*
                 * Camera is back — reset counter.
                 */

                emptyFrameCount = 0;


                /*
                 * Camera is working.
                 */

                handleCameraOnline();


                /*
                 * ------------------------------------------------
                 * YOLO DETECTION
                 * ------------------------------------------------
                 */

                List<Detection> detections =
                        objectDetector.detect(
                                frame
                        );


                /*
                 * ------------------------------------------------
                 * OBJECT TRACKING
                 * ------------------------------------------------
                 *
                 * This is the LIVE camera tracker.
                 */

                List<TrackedObject> trackedObjects =
                        objectTracker.update(
                                detections
                        );


                /*
                 * ------------------------------------------------
                 * LIVE ACTIVITY
                 * ------------------------------------------------
                 *
                 * This updates:
                 *
                 * STATIONARY
                 * WALKING
                 * RUNNING
                 * LOITERING
                 *
                 * etc.
                 */

                liveActivityService.update(
                        trackedObjects
                );


                /*
                 * ------------------------------------------------
                 * REMEMBER CURRENT FINAL ACTIVITY
                 * ------------------------------------------------
                 *
                 * This must happen BEFORE we call
                 * updateTrackingEvents().
                 *
                 * If an object disappears in this frame,
                 * ObjectActivityTracker may remove its state.
                 *
                 * We therefore remember its latest finalActivity.
                 */

                rememberFinalActivities();


                /*
                 * ------------------------------------------------
                 * DATABASE EVENT LIFECYCLE
                 * ------------------------------------------------
                 *
                 * New object:
                 *
                 *     create event
                 *
                 * Existing object:
                 *
                 *     keep event open
                 *
                 * Disappeared object:
                 *
                 *     save final activity
                 *     close event
                 */

                updateTrackingEvents(
                        trackedObjects
                );


                /*
                 * ------------------------------------------------
                 * DRAW BOUNDING BOXES
                 * ------------------------------------------------
                 *
                 * IMPORTANT:
                 *
                 * Only rectangles are drawn.
                 *
                 * No label.
                 * No ID.
                 * No confidence text.
                 */

                drawTrackedObjects(
                        frame,
                        trackedObjects
                );


                /*
                 * ------------------------------------------------
                 * RECORD PROCESSED FRAME
                 * ------------------------------------------------
                 */

                videoRecorder.writeFrame(
                        frame,
                        CAMERA_ID
                );


                /*
                 * ------------------------------------------------
                 * CONSOLE INFORMATION
                 * ------------------------------------------------
                 */

                for (
                        TrackedObject object
                        : trackedObjects
                ) {

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
                 * ------------------------------------------------
                 * SEND FRAME TO BROWSER
                 * ------------------------------------------------
                 */

                updateLatestJpegFrame(
                        frame
                );


            } catch (Exception e) {

                System.err.println(
                        "Camera processing error: "
                                + e.getMessage()
                );


                e.printStackTrace();


                handleCameraOffline();


                sleep(1000);


            } finally {

                if (frame != null) {

                    frame.close();
                }
            }
        }
    }


    /*
     * ============================================================
     * CAMERA ONLINE
     * ============================================================
     */

    private void handleCameraOnline() {

        if (!cameraOnline) {

            cameraOnline = true;


            System.out.println(
                    "CAMERA ONLINE"
            );
        }
    }


    /*
     * ============================================================
     * CAMERA OFFLINE
     * ============================================================
     */

    private void handleCameraOffline() {

        if (cameraOnline) {

            System.out.println(
                    "CAMERA OFFLINE"
            );
        }


        cameraOnline = false;


        /*
         * Remove stale browser frame.
         */

        latestJpegFrame = null;


        /*
         * Finalize all objects currently being tracked.
         */

        closeActiveEvents();


        /*
         * Clear final activity cache.
         */

        lastKnownFinalActivity.clear();


        /*
         * Reset live tracker.
         */

        objectTracker.reset();


        /*
         * Reset live activity.

         */

        liveActivityService.reset();
    }


    /*
     * ============================================================
     * REMEMBER FINAL ACTIVITIES
     * ============================================================
     *
     * Reads the current LiveActivityService state and remembers
     * the final activity for each object.
     */

    private void rememberFinalActivities() {

        List<ObjectActivityTracker.ActivityState>
                activities =
                liveActivityService
                        .getCurrentActivities();


        if (
                activities == null
                        ||
                        activities.isEmpty()
        ) {

            return;
        }


        for (
                ObjectActivityTracker.ActivityState activity
                : activities
        ) {

            int objectId =
                    activity.getObjectId();


            String finalActivity =
                    activity.getFinalActivity();


            if (
                    finalActivity != null
                            &&
                            !finalActivity.isBlank()
            ) {

                lastKnownFinalActivity.put(
                        objectId,
                        finalActivity
                );
            }
        }
    }


    /*
     * ============================================================
     * OBJECT EVENT LIFECYCLE
     * ============================================================
     *
     * One database event per detected object.
     *
     * START:
     *
     *     person #4 detected
     *
     * END:
     *
     *     person #4 -> RUNNING
     *
     * This means the database contains the final activity
     * together with the object's complete start/end lifetime.
     */

    private void updateTrackingEvents(
            List<TrackedObject> trackedObjects
    ) {

        /*
         * IDs that are currently alive.
         */

        Set<Integer> currentTrackedIds =
                new HashSet<>();


        /*
         * --------------------------------------------------------
         * CURRENT OBJECTS
         * --------------------------------------------------------
         */

        for (
                TrackedObject object
                : trackedObjects
        ) {

            int objectId =
                    object.getId();


            currentTrackedIds.add(
                    objectId
            );


            /*
             * ----------------------------------------------------
             * NEW OBJECT
             * ----------------------------------------------------
             */

            if (
                    !activeEvents.containsKey(
                            objectId
                    )
            ) {

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
                 * endTime stays NULL while the object
                 * is being tracked.
                 */

                eventRepository.save(
                        event
                );


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
         * --------------------------------------------------------
         * OBJECTS THAT DISAPPEARED
         * --------------------------------------------------------
         */

        Set<Integer> activeIds =
                new HashSet<>(
                        activeEvents.keySet()
                );


        for (
                Integer objectId
                : activeIds
        ) {

            /*
             * Object is no longer present in
             * ObjectTracker.
             */

            if (
                    !currentTrackedIds.contains(
                            objectId
                    )
            ) {

                Event event =
                        activeEvents.get(
                                objectId
                        );


                if (event != null) {

                    Instant endTime =
                            Instant.now();


                    /*
                     * Get the final activity.
                     */

                    String finalActivity =
                            lastKnownFinalActivity.get(
                                    objectId
                            );


                    /*
                     * Fallback if no activity was available.
                     */

                    if (
                            finalActivity == null
                                    ||
                                    finalActivity.isBlank()
                    ) {

                        finalActivity =
                                "STATIONARY";
                    }


                    /*
                     * ------------------------------------------------
                     * FINAL DATABASE DESCRIPTION
                     * ------------------------------------------------
                     *
                     * Example:
                     *
                     * person #4 → RUNNING
                     *
                     * dog #2 → WALKING
                     *
                     * car #8 → MOVING
                     */

                    String finalDescription =
                            event.getObjectType()
                                    + " #"
                                    + objectId
                                    + " → "
                                    + finalActivity;


                    /*
                     * Update the existing event.
                     *
                     * We do NOT create another database row.
                     */

                    event.setDescription(
                            finalDescription
                    );


                    /*
                     * Mark loitering as anomaly.
                     *
                     * Other activities remain normal.
                     */

                    event.setAnomaly(
                            finalActivity.equals(
                                    "LOITERING"
                            )
                    );


                    /*
                     * Store end time.
                     */

                    event.setEndTime(
                            endTime
                    );


                    /*
                     * Save final event.
                     */

                    eventRepository.save(
                            event
                    );


                    System.out.println(
                            "FINAL ACTIVITY: "
                                    + finalDescription
                                    + " at "
                                    + endTime
                    );
                }


                /*
                 * Remove from active events.
                 */

                activeEvents.remove(
                        objectId
                );


                /*
                 * Remove cached final activity.
                 */

                lastKnownFinalActivity.remove(
                        objectId
                );
            }
        }
    }


    /*
     * ============================================================
     * JPEG FRAME
     * ============================================================
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


    /*
     * ============================================================
     * GET LATEST FRAME
     * ============================================================
     */

    public byte[] getLatestJpegFrame() {

        return latestJpegFrame;
    }


    /*
     * ============================================================
     * CAMERA STATUS
     * ============================================================
     */

    public boolean isRunning() {
        return running.get();
    }


    public boolean isCameraOnline() {
        return running.get() && cameraOnline;
    }


    /*
     * ============================================================
     * DRAW BOUNDING BOXES + LABELS
     * ============================================================
     *
     * Draws a green bounding box with a label like:
     *
     *   person #6  75%
     *
     * displayed above the box, matching the screenshot style.
     */
    private void drawTrackedObjects(
            Mat frame,
            List<TrackedObject> objects
    ) {

        /*
         * Green colour — matches screenshot exactly.
         */
        final Scalar GREEN  = new Scalar(0, 255, 0, 0);
        final Scalar BLACK  = new Scalar(0,   0, 0, 0);

        final int FONT      = opencv_imgproc.FONT_HERSHEY_SIMPLEX;
        final double FONT_SCALE = 0.65;
        final int THICKNESS = 2;
        final int BOX_THICKNESS = 2;


        for (TrackedObject object : objects) {

            Rect box = object.getBoundingBox();


            /*
             * Build label string: "person #6  75%"
             */
            String label =
                    object.getLabel()
                    + " #" + object.getId()
                    + "  "
                    + Math.round(object.getConfidence() * 100)
                    + "%";


            /*
             * Measure label so we can draw a background rect.
             */
            int[] baseLine = {0};
            Size textSize = opencv_imgproc.getTextSize(
                    label, FONT, FONT_SCALE, THICKNESS, baseLine
            );


            /*
             * Text origin: just above the top-left corner of the box.
             * Keep it inside the frame.
             */
            int textX = box.x();
            int textY = Math.max(box.y() - 8, textSize.height() + 4);


            /*
             * Dark background pill behind the text.
             */
            opencv_imgproc.rectangle(
                    frame,
                    new Point(textX - 2, textY - textSize.height() - 4),
                    new Point(textX + textSize.width() + 2, textY + baseLine[0]),
                    BLACK,
                    opencv_imgproc.FILLED,
                    opencv_imgproc.LINE_8,
                    0
            );


            /*
             * Draw label text in green.
             */
            opencv_imgproc.putText(
                    frame,
                    label,
                    new Point(textX, textY),
                    FONT,
                    FONT_SCALE,
                    GREEN,
                    THICKNESS,
                    opencv_imgproc.LINE_AA,
                    false
            );


            /*
             * Draw the bounding box.
             */
            opencv_imgproc.rectangle(
                    frame,
                    box,
                    GREEN,
                    BOX_THICKNESS,
                    opencv_imgproc.LINE_8,
                    0
            );
        }
    }


    /*
     * SLEEP
     */

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


    /*
     * STOP
     */

    @PreDestroy
    public synchronized void stop() {

        if (!running.get()) {
            return;
        }

        running.set(false);

        cameraOnline = false;


        /*
         * Remove stale browser frame.
         */

        latestJpegFrame = null;


        /*
         * Finalize currently active object events.
         */

        closeActiveEvents();


        /*
         * Clear final activity cache.
         */

        lastKnownFinalActivity.clear();


        /*
         * Reset live tracker.
         */

        objectTracker.reset();


        /*
         * Reset activity tracker.

         */

        liveActivityService.reset();


        /*
         * Stop recording.
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

            processingThread = null;
        }


        /*
         * Release camera hardware capture so webcam light turns off.
         */

        cameraSource.stop();


        System.out.println(
                "CCTV camera processing stopped."
        );
    }


    /*
     * CLOSE ACTIVE EVENTS
     * Used when:
     * - camera goes offline
     * - application shuts down
     */

    private void closeActiveEvents() {

        if (activeEvents.isEmpty()) {

            return;
        }


        Instant endTime =
                Instant.now();


        for (
                Event event
                : activeEvents.values()
        ) {

            if (
                    event.getEndTime()
                            == null
            ) {

                /*
                 * Try to get final activity.
                 */

                String finalActivity =
                        lastKnownFinalActivity.get(
                                event.getObjectId()
                        );


                if (
                        finalActivity == null
                                ||
                                finalActivity.isBlank()
                ) {

                    finalActivity =
                            "STATIONARY";
                }


                /*
                 * Final description.
                 */

                String finalDescription =
                        event.getObjectType()
                                + " #"
                                + event.getObjectId()
                                + " → "
                                + finalActivity;


                event.setDescription(
                        finalDescription
                );


                event.setAnomaly(
                        finalActivity.equals(
                                "LOITERING"
                        )
                );


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