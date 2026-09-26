package cctvai.service;

import cctvai.detection.ObjectTracker.TrackedObject;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class LiveActivityService {

    private final ObjectActivityTracker objectActivityTracker;

    /*
     * Latest activity information from the live camera.
     * volatile makes the latest list visible to the
     * REST controller while the camera processing thread
     * is updating it.
     */
    private volatile List<ObjectActivityTracker.ActivityState>
            currentActivities =
            Collections.emptyList();


    public LiveActivityService(
            ObjectActivityTracker objectActivityTracker
    ) {

        this.objectActivityTracker =
                objectActivityTracker;
    }


    /**
     * Called by CameraProcessingService for every
     * processed camera frame.
     */
    public void update(
            List<TrackedObject> trackedObjects
    ) {

        List<ObjectActivityTracker.ActivityState>
                activities =
                objectActivityTracker.update(
                        trackedObjects
                );


        /*
         * Create a separate immutable snapshot.
         */
        currentActivities =
                Collections.unmodifiableList(
                        new ArrayList<>(
                                activities
                        )
                );
    }


    /**
     * Returns the latest activity information.
     */
    public List<ObjectActivityTracker.ActivityState>
    getCurrentActivities() {

        return currentActivities;
    }


    /**
     * Clear all activity information.
     */
    public void reset() {

        objectActivityTracker.reset();

        currentActivities =
                Collections.emptyList();
    }
}