package cctvai.service;

import cctvai.detection.ObjectTracker.TrackedObject;
import org.bytedeco.opencv.opencv_core.Rect;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ObjectActivityTracker {

    /*
     * Minimum movement between frames before
     * the object is considered moving.
     */
    private static final double MOVEMENT_THRESHOLD_PIXELS = 8.0;

    /*
     * Speed threshold for RUNNING / FAST_MOVING.
     *
     * This is based on bounding-box pixel movement,
     * not real-world km/h.
     */
    private static final double RUNNING_SPEED_PIXELS_PER_SECOND = 250.0;

    /*
     * Time without significant movement before
     * the object is considered loitering.
     */
    private static final long LOITERING_SECONDS = 20;

    private final Map<Integer, ObjectState> states =
            new HashMap<>();


    /**
     * Update activity for all currently tracked objects.
     */
    public synchronized List<ActivityState> update(
            List<TrackedObject> trackedObjects
    ) {

        List<ActivityState> result =
                new ArrayList<>();


        Set<Integer> currentIds =
                new HashSet<>();


        Instant now =
                Instant.now();


        for (TrackedObject object :
                trackedObjects) {

            int id =
                    object.getId();


            currentIds.add(
                    id
            );


            Rect box =
                    object.getBoundingBox();


            double centerX =
                    box.x()
                            + (box.width() / 2.0);


            double centerY =
                    box.y()
                            + (box.height() / 2.0);


            ObjectState state =
                    states.get(id);


            /*
             * ------------------------------------------------
             * FIRST DETECTION
             * ------------------------------------------------
             */

            if (state == null) {

                state =
                        new ObjectState(
                                id,
                                object.getLabel(),
                                centerX,
                                centerY,
                                now
                        );


                states.put(
                        id,
                        state
                );
            }


            /*
             * ------------------------------------------------
             * EXISTING OBJECT
             * ------------------------------------------------
             */

            else {

                double dx =
                        centerX
                                - state.lastCenterX;


                double dy =
                        centerY
                                - state.lastCenterY;


                double distance =
                        Math.sqrt(
                                (dx * dx)
                                        + (dy * dy)
                        );


                long elapsedMillis =
                        Math.max(
                                1,
                                Duration.between(
                                        state.lastUpdate,
                                        now
                                ).toMillis()
                        );


                double seconds =
                        elapsedMillis / 1000.0;


                double speed =
                        distance / seconds;


                /*
                 * --------------------------------------------
                 * DETERMINE CURRENT ACTIVITY
                 * --------------------------------------------
                 */

                if (
                        distance
                                >= MOVEMENT_THRESHOLD_PIXELS
                ) {

                    state.stationarySince =
                            null;


                    if (
                            speed
                                    >= RUNNING_SPEED_PIXELS_PER_SECOND
                    ) {

                        state.activity =
                                "RUNNING";
                    }

                    else {

                        state.activity =
                                "WALKING";
                    }
                }

                else {

                    if (
                            state.stationarySince
                                    == null
                    ) {

                        state.stationarySince =
                                now;
                    }


                    long stationarySeconds =
                            Duration.between(
                                    state.stationarySince,
                                    now
                            ).getSeconds();


                    if (
                            stationarySeconds
                                    >= LOITERING_SECONDS
                    ) {

                        state.activity =
                                "LOITERING";
                    }

                    else {

                        state.activity =
                                "STATIONARY";
                    }
                }


                /*
                 * --------------------------------------------
                 * REMEMBER HIGHEST ACTIVITY REACHED
                 * --------------------------------------------
                 */

                updateFinalActivity(
                        state
                );


                state.lastCenterX =
                        centerX;


                state.lastCenterY =
                        centerY;


                state.lastUpdate =
                        now;
            }


            /*
             * First detection should also have
             * a final activity value.
             */
            if (
                    state.finalActivity
                            == null
            ) {

                state.finalActivity =
                        "STATIONARY";
            }


            boolean anomaly =
                    state.activity.equals(
                            "LOITERING"
                    );


            String alertMessage =
                    null;


            if (anomaly) {

                long stationarySeconds =
                        getStationarySeconds(
                                state,
                                now
                        );


                alertMessage =
                        object.getLabel()
                                + " #"
                                + object.getId()
                                + " has remained stationary for "
                                + stationarySeconds
                                + " seconds.";
            }


            long stationarySeconds =
                    getStationarySeconds(
                            state,
                            now
                    );


            result.add(
                    new ActivityState(
                            id,
                            object.getLabel(),
                            object.getConfidence(),
                            state.activity,
                            state.finalActivity,
                            anomaly,
                            alertMessage,
                            stationarySeconds,
                            now
                    )
            );
        }


        /*
         * Remove objects that no longer exist.
         *
         * IMPORTANT:
         *
         * We do not immediately throw away the activity
         * information. CameraProcessingService will use
         * the final activity before removing the object.
         */
        states.keySet().removeIf(
                id -> !currentIds.contains(id)
        );


        return result;
    }


    /**
     * Determine the highest activity reached
     * during the object's lifetime.
     */
    private void updateFinalActivity(
            ObjectState state
    ) {

        String current =
                state.activity;


        String previous =
                state.finalActivity;


        if (previous == null) {

            state.finalActivity =
                    current;

            return;
        }


        /*
         * Activity priority:
         *
         * RUNNING
         * WALKING
         * LOITERING
         * STATIONARY
         */

        if (
                current.equals("RUNNING")
        ) {

            state.finalActivity =
                    "RUNNING";

            return;
        }


        if (
                current.equals("WALKING")
                        &&
                        !previous.equals("RUNNING")
        ) {

            state.finalActivity =
                    "WALKING";

            return;
        }


        if (
                current.equals("LOITERING")
                        &&
                        previous.equals("STATIONARY")
        ) {

            state.finalActivity =
                    "LOITERING";
        }
    }


    /**
     * Get current activity for an object.
     */
    public synchronized ActivityState get(
            int objectId
    ) {

        ObjectState state =
                states.get(objectId);


        if (state == null) {
            return null;
        }


        Instant now =
                Instant.now();


        long stationarySeconds =
                getStationarySeconds(
                        state,
                        now
                );


        boolean anomaly =
                state.activity.equals(
                        "LOITERING"
                );


        String alert =
                anomaly
                        ? state.label
                        + " #"
                        + state.id
                        + " has remained stationary for "
                        + stationarySeconds
                        + " seconds."
                        : null;


        return new ActivityState(
                state.id,
                state.label,
                0.0,
                state.activity,
                state.finalActivity,
                anomaly,
                alert,
                stationarySeconds,
                now
        );
    }


    /**
     * Get final activity before object is removed.
     */
    public synchronized String getFinalActivity(
            int objectId
    ) {

        ObjectState state =
                states.get(objectId);


        if (state == null) {
            return null;
        }


        return state.finalActivity;
    }


    /**
     * Remove an object from the tracker.
     */
    public synchronized void remove(
            int objectId
    ) {

        states.remove(
                objectId
        );
    }


    public synchronized void reset() {

        states.clear();
    }


    private long getStationarySeconds(
            ObjectState state,
            Instant now
    ) {

        if (
                state.stationarySince
                        == null
        ) {

            return 0;
        }


        return Math.max(
                0,
                Duration.between(
                        state.stationarySince,
                        now
                ).getSeconds()
        );
    }


    /**
     * Internal state.
     */
    private static class ObjectState {

        private final int id;

        private final String label;

        private double lastCenterX;

        private double lastCenterY;

        private Instant lastUpdate;

        private Instant stationarySince;

        private String activity =
                "STATIONARY";

        /*
         * Highest/final activity reached
         * during this object's lifetime.
         */
        private String finalActivity =
                "STATIONARY";


        private ObjectState(
                int id,
                String label,
                double centerX,
                double centerY,
                Instant now
        ) {

            this.id =
                    id;

            this.label =
                    label;

            this.lastCenterX =
                    centerX;

            this.lastCenterY =
                    centerY;

            this.lastUpdate =
                    now;

            this.stationarySince =
                    now;
        }
    }


    /**
     * Data returned to the live dashboard.
     */
    public static class ActivityState {

        private final int objectId;

        private final String objectType;

        private final double confidence;

        private final String activity;

        private final String finalActivity;

        private final boolean anomaly;

        private final String alertMessage;

        private final long stationarySeconds;

        private final Instant timestamp;


        public ActivityState(
                int objectId,
                String objectType,
                double confidence,
                String activity,
                String finalActivity,
                boolean anomaly,
                String alertMessage,
                long stationarySeconds,
                Instant timestamp
        ) {

            this.objectId =
                    objectId;

            this.objectType =
                    objectType;

            this.confidence =
                    confidence;

            this.activity =
                    activity;

            this.finalActivity =
                    finalActivity;

            this.anomaly =
                    anomaly;

            this.alertMessage =
                    alertMessage;

            this.stationarySeconds =
                    stationarySeconds;

            this.timestamp =
                    timestamp;
        }


        public int getObjectId() {
            return objectId;
        }


        public String getObjectType() {
            return objectType;
        }


        public double getConfidence() {
            return confidence;
        }


        public String getActivity() {
            return activity;
        }


        public String getFinalActivity() {
            return finalActivity;
        }


        public boolean isAnomaly() {
            return anomaly;
        }


        public String getAlertMessage() {
            return alertMessage;
        }


        public long getStationarySeconds() {
            return stationarySeconds;
        }


        public Instant getTimestamp() {
            return timestamp;
        }
    }
}