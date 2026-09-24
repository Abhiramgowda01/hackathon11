package cctvai.detection;

import org.bytedeco.opencv.opencv_core.Rect;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


/**
 * ============================================================
 * CCTV OBJECT TRACKER
 * ============================================================
 *
 * Gives detected people/animals/objects a persistent ID.
 *
 * Example:
 *
 * Person #1
 * Person #2
 * Dog #3
 *
 * The ID remains the same while the object continues
 * to be detected in nearby positions.
 * ============================================================
 */
@Component
public class ObjectTracker {

    /*
     * If an object is not detected for this many frames,
     * the tracker removes it.
     *
     * Example:
     * 15 missed frames -> object is removed.
     */
    private static final int MAX_MISSED_FRAMES = 15;

    /*
     * Minimum IoU required to consider two boxes
     * as the same object.
     */
    private static final double IOU_THRESHOLD = 0.30;

    /*
     * Next tracking ID.
     */
    private int nextId = 1;

    /*
     * Objects currently being tracked.
     */
    private final List<TrackedObject> trackedObjects =
            new ArrayList<>();


    /**
     * ========================================================
     * UPDATE TRACKER
     * ========================================================
     *
     * Receives the detections from YOLO for the current frame.
     *
     * It tries to match each detection with an existing object.
     *
     * If a match is found:
     *      keep the same ID
     *
     * If no match is found:
     *      create a new ID
     *
     * @param detections detections from ObjectDetector
     * @return currently tracked objects
     */
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
         * Try to match every new detection
         * with an existing tracked object.
         */
        for (Detection detection : detections) {

            TrackedObject bestMatch = null;
            double bestIoU = 0.0;


            /*
             * Compare this detection with every
             * currently tracked object.
             */
            for (TrackedObject existing : trackedObjects) {

                /*
                 * This object has already been matched
                 * with another detection in this frame.
                 */
                if (existing.isMatched()) {
                    continue;
                }


                /*
                 * Do not match different object types.
                 *
                 * Example:
                 *
                 * person != dog
                 * car != person
                 */
                if (!existing.getLabel()
                        .equalsIgnoreCase(
                                detection.getLabel()
                        )) {

                    continue;
                }


                /*
                 * Calculate overlap between the
                 * existing box and new detection box.
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
             * ====================================================
             * EXISTING OBJECT FOUND
             * ====================================================
             */
            if (bestMatch != null
                    && bestIoU >= IOU_THRESHOLD) {

                bestMatch.update(
                        detection.getBoundingBox(),
                        detection.getConfidence()
                );

            } else {

                /*
                 * =================================================
                 * NEW OBJECT FOUND
                 * =================================================
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
         * ========================================================
         * HANDLE MISSED OBJECTS
         * ========================================================
         *
         * If an object was not detected in this frame,
         * increase its missed-frame counter.
         */
        for (TrackedObject object : trackedObjects) {

            if (!object.isMatched()) {

                object.incrementMissedFrames();
            }
        }


        /*
         * ========================================================
         * REMOVE DISAPPEARED OBJECTS
         * ========================================================
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
         *
         * This prevents outside code from directly
         * modifying our internal tracking list.
         */
        return new ArrayList<>(trackedObjects);
    }


    /**
     * ========================================================
     * RESET TRACKER
     * ========================================================
     *
     * Used when a new uploaded video starts.
     *
     * Example:
     *
     * Video 1:
     *      person #1
     *      dog #2
     *
     * New Video:
     *      person #1
     *      dog #2
     *
     * This prevents tracking IDs from continuing
     * from the previous video.
     */
    public synchronized void reset() {

        trackedObjects.clear();

        nextId = 1;

        System.out.println(
                "OBJECT TRACKER RESET"
        );
    }


    /**
     * ========================================================
     * CALCULATE IoU
     * ========================================================
     *
     * Intersection over Union.
     *
     * Used to determine whether two bounding boxes
     * belong to the same object.
     */
    private double calculateIoU(
            Rect a,
            Rect b
    ) {

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
     * ========================================================
     * TRACKED OBJECT
     * ========================================================
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