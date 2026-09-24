package cctvai.detection;

import org.bytedeco.opencv.opencv_core.Rect;

public class Detection {

    private final String label;
    private final float confidence;
    private final Rect boundingBox;

    public Detection(
            String label,
            float confidence,
            Rect boundingBox
    ) {
        this.label = label;
        this.confidence = confidence;
        this.boundingBox = boundingBox;
    }

    public String getLabel() {
        return label;
    }

    public float getConfidence() {
        return confidence;
    }

    public Rect getBoundingBox() {
        return boundingBox;
    }
}