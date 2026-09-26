package cctvai.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "events")
public class Event {

    /*
     * PRIMARY KEY
     */

    @Id
    private String id = UUID.randomUUID().toString();


    /*
     * CAMERA
     */

    @Column(nullable = false)
    private String cameraId;


    /*
     * OBJECT TRACKING
     * ID assigned by ObjectTracker.
     * Example:
     * person #1
     * dog #2
     * car #3
     */
    @Column
    private Integer objectId;
    /*
     * Object type detected by YOLO.
     * Examples:
     * person
     * cat
     * dog
     * sheep
     * car
     */

    @Column
    private String objectType;


    /*
     * OBJECT LIFETIME
     * When the tracked object first appeared.
     */

    @Column
    private Instant startTime;


    /*
     * When the tracked object disappeared.
     * NULL while the object is still active.
     */

    @Column
    private Instant endTime;


    /*
     * EXISTING EVENT TIMESTAMP
     * Kept for compatibility with the existing
     * dashboard/API.
     */

    @Column(nullable = false)
    private Instant timestamp = Instant.now();


    /*
     * DESCRIPTION
     *
     * Examples:
     *
     * While active:
     *
     * person #1 detected
     *
     * After object disappears:
     *
     * person #1 → RUNNING
     *
     * dog #2 → WALKING
     *
     * car #3 → MOVING
     */

    @Column(length = 2000, nullable = false)
    private String description;


    /*
     * ANOMALY
     * true  = unusual/anomalous activity
     * false = normal activity
     */

    @Column(nullable = false)
    private boolean anomaly;


    /*
     * SIMILARITY
     *
     * Kept for compatibility with the existing event system.
     */

    private Double similarity;


    /*
     * DEFAULT CONSTRUCTOR
     * Required by JPA.
     */

    public Event() {
    }


    /*
     * EXISTING EVENT CONSTRUCTOR
     * Kept so existing code does not break.
     */

    public Event(
            String cameraId,
            String description,
            boolean anomaly,
            Double similarity
    ) {

        this.cameraId =
                cameraId;

        this.description =
                description;

        this.anomaly =
                anomaly;

        this.similarity =
                similarity;
    }


    /*
     * CCTV TRACKING EVENT CONSTRUCTOR
     * Used by CameraProcessingService.
     * Example:
     * new Event(
     *     "webcam-0",
     *     4,
     *     "person",
     *     startTime,
     *     "person #4 detected"
     * );
     */

    public Event(
            String cameraId,
            Integer objectId,
            String objectType,
            Instant startTime,
            String description
    ) {

        this.cameraId =
                cameraId;

        this.objectId =
                objectId;

        this.objectType =
                objectType;

        this.startTime =
                startTime;

        this.timestamp =
                startTime;

        this.description =
                description;

        this.anomaly =
                false;

        this.similarity =
                null;
    }


    /*
     * GETTERS
     */

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


    /*
     * SETTERS
     */


    /**
     * Set the event end time.
     * Called when the tracked object disappears.
     */
    public void setEndTime(
            Instant endTime
    ) {

        this.endTime =
                endTime;
    }


    /**
     * Update the final event description.
     */
    public void setDescription(
            String description
    ) {

        this.description =
                description;
    }


    /**
     * Update anomaly status.
     */
    public void setAnomaly(
            boolean anomaly
    ) {

        this.anomaly =
                anomaly;
    }
}