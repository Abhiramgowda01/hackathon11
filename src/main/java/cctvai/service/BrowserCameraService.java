package cctvai.service;

import cctvai.detection.Detection;
import cctvai.detection.ObjectDetector;
import cctvai.detection.ObjectTracker;
import cctvai.detection.ObjectTracker.TrackedObject;
import cctvai.model.Event;
import cctvai.repository.EventRepository;

import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.opencv.opencv_core.Mat;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.bytedeco.opencv.global.opencv_core.CV_8UC1;
import static org.bytedeco.opencv.global.opencv_imgcodecs.IMREAD_COLOR;
import static org.bytedeco.opencv.global.opencv_imgcodecs.imdecode;

@Service
public class BrowserCameraService {

    private final ObjectDetector objectDetector;

    private final EventRepository eventRepository;

    /*
     * Each browser session gets its own ObjectTracker.
     */
    private final Map<String, ObjectTracker> trackers =
            new ConcurrentHashMap<>();

    /*
     * Each browser session gets its own activity tracker.
     */
    private final Map<String, ObjectActivityTracker> activityTrackers =
            new ConcurrentHashMap<>();

    /*
     * Active events:
     * sessionId
     *     -> objectId
     *         -> Event
     */
    private final Map<String, Map<Integer, Event>> activeEvents =
            new ConcurrentHashMap<>();

    /*
     * Final activity reached by each tracked object.
     */
    private final Map<String, Map<Integer, String>> finalActivities =
            new ConcurrentHashMap<>();

    /*
     * Object type for each tracked object.
     */
    private final Map<String, Map<Integer, String>> objectTypes =
            new ConcurrentHashMap<>();


    public BrowserCameraService(
            ObjectDetector objectDetector,
            EventRepository eventRepository
    ) {
        this.objectDetector = objectDetector;
        this.eventRepository = eventRepository;
    }


    /**
     * Process one JPEG frame received from a browser webcam.
     *
     * IMPORTANT:
     *
     * The original webcam recording is NOT stored here.
     *
     * Only the individual analysis frame is received,
     * processed and then released.
     */
    public Map<String, Object> processFrame(
            String sessionId,
            MultipartFile frame
    ) throws Exception {

        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException(
                    "sessionId is required"
            );
        }
        if (frame == null || frame.isEmpty()) {
            throw new IllegalArgumentException(
                    "Camera frame is empty"
            );
        }


        /*
         * Get/create tracker for this browser.
         */
        ObjectTracker tracker =
                trackers.computeIfAbsent(
                        sessionId,
                        key -> new ObjectTracker()
                );


        /*
         * Get/create activity tracker for this browser
         */
        ObjectActivityTracker activityTracker =
                activityTrackers.computeIfAbsent(
                        sessionId,
                        key -> new ObjectActivityTracker()
                );


        /*
         * Create event maps for this browser
         */
        activeEvents.computeIfAbsent(
                sessionId,
                key -> new ConcurrentHashMap<>()
        );

        finalActivities.computeIfAbsent(
                sessionId,
                key -> new ConcurrentHashMap<>()
        );

        objectTypes.computeIfAbsent(
                sessionId,
                key -> new ConcurrentHashMap<>()
        );


        /*
         * Convert uploaded JPEG bytes to OpenCV Mat.
         */
        byte[] bytes = frame.getBytes();

        BytePointer bytePointer =
                new BytePointer(bytes);

        Mat encoded =
                new Mat(
                        1,
                        bytes.length,
                        CV_8UC1,
                        bytePointer
                );

        Mat image =
                imdecode(
                        encoded,
                        IMREAD_COLOR
                );


        if (image == null || image.empty()) {

            encoded.release();
            bytePointer.deallocate();

            throw new IllegalArgumentException(
                    "Could not decode browser camera frame."
            );
        }


        /*
         * YOLO detection.
         */
        List<Detection> detections =
                objectDetector.detect(image);

        if (detections == null) {
            detections =
                    Collections.emptyList();
        }


        /*
         * Object tracking.
         */
        List<TrackedObject> trackedObjects =
                tracker.update(detections);

        if (trackedObjects == null) {
            trackedObjects =
                    Collections.emptyList();
        }


        /*
         * Activity tracking.
         */
        List<ObjectActivityTracker.ActivityState> activities =
                activityTracker.update(
                        trackedObjects
                );

        if (activities == null) {
            activities =
                    Collections.emptyList();
        }


        /*
         * Remember activity.
         */
        rememberActivities(
                sessionId,
                activities
        );


        /*
         * Create/maintain/finish events.
         */
        updateEvents(
                sessionId,
                trackedObjects
        );


        /*
         * Build response for browser.
         */
        List<Map<String, Object>> detectedObjects =
                new ArrayList<>();


        for (TrackedObject object : trackedObjects) {

            Map<String, Object> objectData =
                    new java.util.HashMap<>();

            objectData.put(
                    "objectId",
                    object.getId()
            );

            objectData.put(
                    "objectType",
                    object.getLabel()
            );

            objectData.put(
                    "confidence",
                    object.getConfidence()
            );

            /*
             * Bounding box.
             * These coordinates will later be used
             * by the browser to draw the green box.
             */
            if (object.getBoundingBox() != null) {

                objectData.put(
                        "x",
                        object.getBoundingBox().x()
                );

                objectData.put(
                        "y",
                        object.getBoundingBox().y()
                );

                objectData.put(
                        "width",
                        object.getBoundingBox().width()
                );

                objectData.put(
                        "height",
                        object.getBoundingBox().height()
                );
            }

            detectedObjects.add(
                    objectData
            );
        }


        /*
         * Live activity response.
         */
        List<Map<String, Object>> liveActivities =
                new ArrayList<>();


        for (
                ObjectActivityTracker.ActivityState activity :
                activities
        ) {

            liveActivities.add(
                    Map.of(
                            "objectId",
                            activity.getObjectId(),

                            "objectType",
                            activity.getObjectType(),

                            "activity",
                            activity.getActivity(),

                            "finalActivity",
                            activity.getFinalActivity(),

                            "confidence",
                            activity.getConfidence(),

                            "anomaly",
                            activity.isAnomaly(),

                            "alertMessage",
                            activity.getAlertMessage() == null
                                    ? ""
                                    : activity.getAlertMessage(),

                            "stationarySeconds",
                            activity.getStationarySeconds()
                    )
            );
        }


        /*
         * Release OpenCV memory
         */
        image.release();
        encoded.release();
        bytePointer.deallocate();


        /*
         * Return result.
         */
        return Map.of(
                "sessionId",
                sessionId,

                "detectedObjects",
                detectedObjects,

                "activities",
                liveActivities,

                "objectCount",
                trackedObjects.size()
        );
    }


    /**
     * Remember the latest/final activity.
     */
    private void rememberActivities(
            String sessionId,
            List<ObjectActivityTracker.ActivityState> activities
    ) {

        Map<Integer, String> finalActivityMap =
                finalActivities.get(sessionId);

        Map<Integer, String> objectTypeMap =
                objectTypes.get(sessionId);


        for (
                ObjectActivityTracker.ActivityState activity :
                activities
        ) {

            finalActivityMap.put(
                    activity.getObjectId(),
                    activity.getFinalActivity()
            );

            objectTypeMap.put(
                    activity.getObjectId(),
                    activity.getObjectType()
            );
        }
    }


    /**
     * Maintain one Event per object lifetime.
     */
    private void updateEvents(
            String sessionId,
            List<TrackedObject> trackedObjects
    ) {

        Map<Integer, Event> sessionEvents =
                activeEvents.get(sessionId);

        Map<Integer, String> finalActivityMap =
                finalActivities.get(sessionId);

        Map<Integer, String> objectTypeMap =
                objectTypes.get(sessionId);

        ObjectTracker tracker =
                trackers.get(sessionId);

        Instant now =
                Instant.now();


        /*
         * IDs currently tracked.
         */
        Map<Integer, TrackedObject> currentObjects =
                new ConcurrentHashMap<>();


        for (TrackedObject object : trackedObjects) {

            int objectId =
                    object.getId();

            currentObjects.put(
                    objectId,
                    object
            );


            /*
             * Start a new event only once.
             */
            if (!sessionEvents.containsKey(objectId)) {

                Event event =
                        new Event(
                                sessionId,
                                objectId,
                                object.getLabel(),
                                now,
                                object.getLabel()
                                        + " #"
                                        + objectId
                                        + " detected"
                        );

                sessionEvents.put(
                        objectId,
                        event
                );

                eventRepository.save(event);
            }
        }


        /*
         * Check events whose objects have disappeared.
         */
        List<Integer> eventIds =
                new ArrayList<>(
                        sessionEvents.keySet()
                );


        for (Integer objectId : eventIds) {

            /*
             * Object is still in tracker.
             */
            if (currentObjects.containsKey(objectId)) {
                continue;
            }


            /*
             * Safety check.
             *
             * ObjectTracker keeps objects alive for
             * MAX_MISSED_FRAMES, so this normally means
             * the object was actually removed.
             */
            if (tracker != null &&
                    tracker.containsObject(objectId)) {

                continue;
            }


            Event event =
                    sessionEvents.remove(objectId);

            if (event == null) {
                continue;
            }


            String objectType =
                    objectTypeMap.get(objectId);

            if (objectType == null) {
                objectType =
                        event.getObjectType();
            }


            String finalActivity =
                    finalActivityMap.get(objectId);

            if (finalActivity == null ||
                    finalActivity.isBlank()) {

                finalActivity =
                        "STATIONARY";
            }


            /*
             * Final event:
             *
             * person #4 → RUNNING
             */
            event.setDescription(
                    objectType
                            + " #"
                            + objectId
                            + " → "
                            + finalActivity
            );


            /*
             * LOITERING is currently considered
             * an anomaly by the existing activity system.
             */
            event.setAnomaly(
                    "LOITERING".equalsIgnoreCase(
                            finalActivity
                    )
            );


            event.setEndTime(
                    now
            );


            eventRepository.save(
                    event
            );


            finalActivityMap.remove(
                    objectId
            );

            objectTypeMap.remove(
                    objectId
            );
        }
    }


    /**
     * Close everything belonging to one browser session.
     *
     * This does NOT save a video.
     */
    public synchronized void closeSession(
            String sessionId
    ) {

        Map<Integer, Event> sessionEvents =
                activeEvents.remove(
                        sessionId
                );

        Map<Integer, String> finalActivityMap =
                finalActivities.remove(
                        sessionId
                );

        objectTypes.remove(
                sessionId
        );


        ObjectTracker tracker =
                trackers.remove(
                        sessionId
                );

        ObjectActivityTracker activityTracker =
                activityTrackers.remove(
                        sessionId
                );


        if (sessionEvents != null) {

            Instant now =
                    Instant.now();


            for (Event event :
                    sessionEvents.values()) {

                Integer objectId =
                        event.getObjectId();


                String finalActivity =
                        finalActivityMap == null
                                ? null
                                : finalActivityMap.get(
                                objectId
                        );


                if (finalActivity == null ||
                        finalActivity.isBlank()) {

                    finalActivity =
                            "STATIONARY";
                }


                String objectType =
                        event.getObjectType();


                event.setDescription(
                        objectType
                                + " #"
                                + objectId
                                + " → "
                                + finalActivity
                );


                event.setAnomaly(
                        "LOITERING".equalsIgnoreCase(
                                finalActivity
                        )
                );


                event.setEndTime(
                        now
                );


                eventRepository.save(
                        event
                );
            }
        }


        /*
         * Reset tracker state.
         */
        if (tracker != null) {

            tracker.reset();
        }


        if (activityTracker != null) {

            activityTracker.reset();
        }
    }


    /**
     * Convenience cleanup method.
     */
    public void cleanupSession(
            String sessionId
    ) {

        closeSession(
                sessionId
        );
    }
}