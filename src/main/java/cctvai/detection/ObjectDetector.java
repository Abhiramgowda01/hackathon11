package cctvai.detection;

import org.bytedeco.opencv.global.opencv_dnn;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.MatVector;
import org.bytedeco.opencv.opencv_core.Rect;
import org.bytedeco.opencv.opencv_core.Size;
import org.bytedeco.opencv.opencv_dnn.Net;
import org.bytedeco.javacpp.indexer.FloatIndexer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ObjectDetector {

    private static final String MODEL_PATH = "models/yolov8n.onnx";

    private static final int INPUT_WIDTH = 640;
    private static final int INPUT_HEIGHT = 640;

    private static final float CONFIDENCE_THRESHOLD = 0.30f;
    private static final float NMS_THRESHOLD = 0.45f;

    private static final String[] CLASS_NAMES = {
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier", "toothbrush"
    };

    private static final java.util.Set<String> TARGET_CLASSES = java.util.Set.of(
            "person", "bicycle", "car", "motorcycle", "bus", "truck",
            "backpack", "handbag", "suitcase", "umbrella",
            "knife", "baseball bat", "scissors", "bottle",
            "cell phone", "laptop", "book",
            "dog", "cat", "chair", "couch", "bench"
    );

    private final Net net;

    public ObjectDetector() {

        System.out.println("==========================================");
        System.out.println("Loading YOLO model...");
        System.out.println("Model: " + MODEL_PATH);

        net = opencv_dnn.readNetFromONNX(MODEL_PATH);

        if (net.empty()) {
            throw new IllegalStateException(
                    "Could not load YOLO model: " + MODEL_PATH
            );
        }
        net.setPreferableBackend(opencv_dnn.DNN_BACKEND_OPENCV);
        net.setPreferableTarget(opencv_dnn.DNN_TARGET_CPU);

        System.out.println("YOLO model loaded successfully.");
        System.out.println("==========================================");
    }


    public synchronized List<Detection> detect(Mat frame) {

        if (frame == null || frame.empty()) {
            return List.of();
        }

        int originalWidth = frame.cols();
        int originalHeight = frame.rows();


        Mat blob = opencv_dnn.blobFromImage(
                frame,
                1.0 / 255.0,
                new Size(INPUT_WIDTH, INPUT_HEIGHT),
                new org.bytedeco.opencv.opencv_core.Scalar(
                        0,
                        0,
                        0,
                        0
                ),
                true,
                false,
                org.bytedeco.opencv.global.opencv_core.CV_32F
        );

        net.setInput(blob);

        MatVector outputs = new MatVector();

        net.forward(
                outputs,
                net.getUnconnectedOutLayersNames()
        );

        if (outputs.size() == 0) {
            blob.close();
            outputs.close();
            return List.of();
        }

        Mat output = outputs.get(0);

        List<Detection> detections = parseOutput(
                output,
                originalWidth,
                originalHeight
        );

        output.close();
        outputs.close();
        blob.close();

        return detections;
    }

    private List<Detection> parseOutput(
            Mat output,
            int originalWidth,
            int originalHeight
    ) {

        List<Candidate> candidates = new ArrayList<>();

        if (output.dims() != 3) {
            return List.of();
        }

        int channels = (int) output.size(1);
        int detectionsCount = (int) output.size(2);

        if (channels < 5) {
            return List.of();
        }

        FloatIndexer indexer = output.createIndexer();

        float xScale = (float) originalWidth / INPUT_WIDTH;
        float yScale = (float) originalHeight / INPUT_HEIGHT;

        for (int i = 0; i < detectionsCount; i++) {

            float centerX = indexer.get(0, 0, i);
            float centerY = indexer.get(0, 1, i);

            float width = indexer.get(0, 2, i);
            float height = indexer.get(0, 3, i);

            float bestConfidence = 0.0f;
            int bestClass = -1;


            for (int classIndex = 4; classIndex < channels; classIndex++) {

                float confidence =
                        indexer.get(0, classIndex, i);

                if (confidence > bestConfidence) {
                    bestConfidence = confidence;
                    bestClass = classIndex - 4;
                }
            }

            if (bestClass < 0) {
                continue;
            }

            if (bestClass >= CLASS_NAMES.length) {
                continue;
            }

            String label = CLASS_NAMES[bestClass];

            if (!TARGET_CLASSES.contains(label)) {
                continue;
            }

            if (bestConfidence < CONFIDENCE_THRESHOLD) {
                continue;
            }


            int x = Math.round(
                    (centerX - width / 2.0f) * xScale
            );

            int y = Math.round(
                    (centerY - height / 2.0f) * yScale
            );

            int boxWidth = Math.round(width * xScale);
            int boxHeight = Math.round(height * yScale);

            /*
             * Keep bounding box inside the image.
             */
            x = Math.max(0, x);
            y = Math.max(0, y);

            boxWidth = Math.min(
                    boxWidth,
                    originalWidth - x
            );

            boxHeight = Math.min(
                    boxHeight,
                    originalHeight - y
            );

            if (boxWidth <= 0 || boxHeight <= 0) {
                continue;
            }

            Rect rectangle = new Rect(
                    x,
                    y,
                    boxWidth,
                    boxHeight
            );

            candidates.add(
                    new Candidate(
                            label,
                            bestConfidence,
                            rectangle
                    )
            );
        }

        indexer.release();

        /*
         * Apply Non-Maximum Suppression.
         *
         * This removes duplicate boxes around the same object.
         */
        List<Candidate> selected =
                applyNms(candidates);

        List<Detection> result = new ArrayList<>();

        for (Candidate candidate : selected) {

            result.add(
                    new Detection(
                            candidate.label,
                            candidate.confidence,
                            candidate.rectangle
                    )
            );
        }

        return result;
    }

    /**
     * Simple Non-Maximum Suppression.
     */
    private List<Candidate> applyNms(
            List<Candidate> candidates
    ) {

        List<Candidate> result = new ArrayList<>();

        candidates.sort(
                (a, b) ->
                        Float.compare(
                                b.confidence,
                                a.confidence
                        )
        );

        boolean[] removed =
                new boolean[candidates.size()];

        for (int i = 0; i < candidates.size(); i++) {

            if (removed[i]) {
                continue;
            }

            Candidate current = candidates.get(i);

            result.add(current);

            for (int j = i + 1; j < candidates.size(); j++) {

                if (removed[j]) {
                    continue;
                }

                Candidate other = candidates.get(j);

                if (!current.label.equals(other.label)) {
                    continue;
                }

                double iou = calculateIoU(
                        current.rectangle,
                        other.rectangle
                );

                if (iou > NMS_THRESHOLD) {
                    removed[j] = true;
                }
            }
        }

        return result;
    }

    /**
     * Calculate Intersection over Union.
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
                Math.max(0, right - left);

        int intersectionHeight =
                Math.max(0, bottom - top);

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
                areaA + areaB - intersection;

        if (union <= 0) {
            return 0.0;
        }

        return intersection / union;
    }

    /**
     * Internal detection candidate.
     */
    private static class Candidate {

        private final String label;
        private final float confidence;
        private final Rect rectangle;

        private Candidate(
                String label,
                float confidence,
                Rect rectangle
        ) {
            this.label = label;
            this.confidence = confidence;
            this.rectangle = rectangle;
        }
    }

    public float getConfidenceThreshold() {
        return CONFIDENCE_THRESHOLD;
    }

    public List<String> getTargetClasses() {
        return new ArrayList<>(TARGET_CLASSES);
    }
}