package cctvai.detection;

import org.bytedeco.opencv.opencv_core.Rect;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

@Component
public class ObjectTracker {

    private static final int MAX_MISSED_FRAMES = 15;

    private static final double IOU_THRESHOLD = 0.30;

    private int nextId = 1;

    private final List<TrackedObject> trackedObjects =
            new ArrayList<>();



    public synchronized List<TrackedObject> update(
            List<Detection> detections
    ) {

        /*
         * Mark every existing object as unmatched.
         */
        for (TrackedObject object : trackedObjects) {
            object.setMatched(false);
        }


        /*
          Try to match every new detection with an existing tracked object.
         */
        for (Detection detection : detections) {

            TrackedObject bestMatch = null;
            double bestIoU = 0.0;


            /*
             * Compare this detection with every currently tracked object.
             */
            for (TrackedObject existing : trackedObjects) {

                /*
                 * Already matched in this frame.
                 */
                if (existing.isMatched()) {
                    continue;
                }
                /*
                 * Do not match different object types.
                 */
                if (!existing.getLabel()
                        .equalsIgnoreCase(
                                detection.getLabel()
                        )) {

                    continue;
                }


                /*
                 * Calculate overlap between the existing box and new detection box.
                 */
                double iou = calculateIoU(
                        existing.getBoundingBox(),
                        detection.getBoundingBox()
                );


                /*
                 * Keep the best matching object.
                 */
                if (iou > bestIoU) {

                    bestIoU = iou;
                    bestMatch = existing;
                }
            }


            /*
             * EXISTING OBJECT FOUND
             */
            if (bestMatch != null
                    && bestIoU >= IOU_THRESHOLD) {

                bestMatch.update(
                        detection.getBoundingBox(),
                        detection.getConfidence()
                );

            } else {

                /*
                 * NEW OBJECT FOUND
                 */
                TrackedObject newObject =
                        new TrackedObject(
                                nextId++,
                                detection.getLabel(),
                                detection.getConfidence(),
                                detection.getBoundingBox()
                        );

                newObject.setMatched(true);

                trackedObjects.add(newObject);
            }
        }


        /*
         * HANDLE MISSED OBJECTS
         */
        for (TrackedObject object : trackedObjects) {

            if (!object.isMatched()) {

                object.incrementMissedFrames();
            }
        }


        /*
         * REMOVE DISAPPEARED OBJECTS
         */
        Iterator<TrackedObject> iterator =
                trackedObjects.iterator();

        while (iterator.hasNext()) {

            TrackedObject object =
                    iterator.next();

            if (object.getMissedFrames()
                    > MAX_MISSED_FRAMES) {

                iterator.remove();
            }
        }


        /*
         * Return a copy.
         */
        return new ArrayList<>(trackedObjects);
    }


    /**
     * CHECK WHETHER OBJECT IS CURRENTLY TRACKER
     * Used by the event system to determine whether
     * an object still exists in the tracker.
     */
    public synchronized boolean containsObject(
            int objectId
    ) {
        for (TrackedObject object : trackedObjects) {

            if (object.getId() == objectId) {

                return true;
            }
        }
        return false;
    }


    /**
     * RESET TRACKER
     */
    public synchronized void reset() {

        trackedObjects.clear();

        nextId = 1;

        System.out.println(
                "OBJECT TRACKER RESET"
        );
    }


    /**
     * CALCULATE IoU
     * Intersection over Union.
     */
    private double calculateIoU(
            Rect a,
            Rect b
    ) {

        if (a == null || b == null) {

            return 0.0;
        }


        int left = Math.max(
                a.x(),
                b.x()
        );

        int top = Math.max(
                a.y(),
                b.y()
        );

        int right = Math.min(
                a.x() + a.width(),
                b.x() + b.width()
        );

        int bottom = Math.min(
                a.y() + a.height(),
                b.y() + b.height()
        );


        int intersectionWidth =
                Math.max(
                        0,
                        right - left
                );

        int intersectionHeight =
                Math.max(
                        0,
                        bottom - top
                );


        double intersection =
                (double) intersectionWidth
                        * intersectionHeight;


        double areaA =
                (double) a.width()
                        * a.height();


        double areaB =
                (double) b.width()
                        * b.height();


        double union =
                areaA
                        + areaB
                        - intersection;


        if (union <= 0) {

            return 0.0;
        }


        return intersection / union;
    }


    /**
     * TRACKED OBJECT
     */
    public static class TrackedObject {

        private final int id;

        private final String label;

        private float confidence;

        private Rect boundingBox;

        private int missedFrames;

        private boolean matched;


        /**
         * Constructor.
         */
        public TrackedObject(
                int id,
                String label,
                float confidence,
                Rect boundingBox
        ) {

            this.id = id;

            this.label = label;

            this.confidence = confidence;

            this.boundingBox = boundingBox;

            this.missedFrames = 0;

            this.matched = true;
        }


        /**
         * Update this object with
         * the latest detection.
         */
        public void update(
                Rect newBoundingBox,
                float newConfidence
        ) {

            this.boundingBox =
                    newBoundingBox;

            this.confidence =
                    newConfidence;

            this.missedFrames = 0;

            this.matched = true;
        }


        /**
         * Increase missed-frame counter.
         */
        public void incrementMissedFrames() {

            this.missedFrames++;
        }


        /**
         * Get tracking ID.
         */
        public int getId() {

            return id;
        }


        /**
         * Get object label.
         */
        public String getLabel() {

            return label;
        }


        /**
         * Get detection confidence.
         */
        public float getConfidence() {

            return confidence;
        }


        /**
         * Get bounding box.
         */
        public Rect getBoundingBox() {

            return boundingBox;
        }


        /**
         * Get missed-frame count.
         */
        public int getMissedFrames() {

            return missedFrames;
        }


        /**
         * Check whether this object
         * was matched during current frame.
         */
        public boolean isMatched() {

            return matched;
        }


        /**
         * Set matched state.
         */
        public void setMatched(
                boolean matched
        ) {

            this.matched = matched;
        }
    }
}