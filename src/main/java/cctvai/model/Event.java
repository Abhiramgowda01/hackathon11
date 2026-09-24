package cctvai.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class Event {

    @Id
    private String id = UUID.randomUUID().toString();

    @Column(nullable = false)
    private String cameraId;

    /*
     * ID assigned by ObjectTracker.
     * Example: 1, 2, 3...
     */
    @Column
    private Integer objectId;

    /*
     * Object type detected by YOLO.
     * Examples: person, cat, dog...
     */
    @Column
    private String objectType;

    /*
     * When this tracked object first appeared.
     */
    @Column
    private Instant startTime;

    /*
     * When this tracked object disappeared.
     *
     * NULL while the object is still active.
     */
    @Column
    private Instant endTime;

    /*
     * Existing event timestamp.
     *
     * We keep this field so your existing
     * dashboard/API continues to work.
     */
    @Column(nullable = false)
    private Instant timestamp = Instant.now();

    @Column(length = 2000, nullable = false)
    private String description;

    @Column(nullable = false)
    private boolean anomaly;

    private Double similarity;

    public Event() {
    }

    /*
     * Existing constructor.
     *
     * Kept so existing code does not break.
     */
    public Event(
            String cameraId,
            String description,
            boolean anomaly,
            Double similarity
    ) {
        this.cameraId = cameraId;
        this.description = description;
        this.anomaly = anomaly;
        this.similarity = similarity;
    }

    /*
     * New constructor for CCTV tracking events.
     */
    public Event(
            String cameraId,
            Integer objectId,
            String objectType,
            Instant startTime,
            String description
    ) {
        this.cameraId = cameraId;
        this.objectId = objectId;
        this.objectType = objectType;
        this.startTime = startTime;
        this.timestamp = startTime;
        this.description = description;
        this.anomaly = false;
        this.similarity = null;
    }

    public String getId() {
        return id;
    }

    public String getCameraId() {
        return cameraId;
    }

    public Integer getObjectId() {
        return objectId;
    }

    public String getObjectType() {
        return objectType;
    }

    public Instant getStartTime() {
        return startTime;
    }

    public Instant getEndTime() {
        return endTime;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public String getDescription() {
        return description;
    }

    public boolean isAnomaly() {
        return anomaly;
    }

    public Double getSimilarity() {
        return similarity;
    }

    public void setEndTime(Instant endTime) {
        this.endTime = endTime;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}