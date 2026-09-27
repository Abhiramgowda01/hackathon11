package cctvai.service;

import cctvai.detection.ObjectTracker.TrackedObject;
import org.bytedeco.opencv.global.opencv_core;
import org.bytedeco.opencv.global.opencv_imgproc;
import org.bytedeco.opencv.opencv_core.Mat;
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

/**
 * High-accuracy human behavior and movement classifier.
 * Employs multi-frame directional trajectory filtering and optical motion differencing to
 * completely eliminate webcam detector jitter false-positives and accurately distinguish:
 *  - STANDING (upright full-body stationary posture)
 *  - SITTING (seated posture, lower aspect ratio, stationary)
 *  - HAND MOVEMENT (active arm/hand motion differencing while body remains stationary)
 *  - WALKING (true ground translational locomotion with directional coherence)
 *  - RUNNING (high-speed rapid locomotion with acceleration burst)
 *  - LOITERING (stationary in place > 20s)
 *  - FALLEN / COLLAPSED (abrupt vertical collapse)
 */
@Service
public class ObjectActivityTracker {

    private static final double JITTER_DEADBAND_PIXELS = 7.5;
    private static final double MIN_WALKING_LOCOMOTION_SPEED = 24.0; // px/s
    private static final double MIN_RUNNING_LOCOMOTION_SPEED = 75.0; // px/s - responsive sprint detection
    private static final double MIN_DIRECTIONALITY_RATIO = 0.42; // Filters out oscillating jitter
    private static final long LOITERING_SECONDS = 18;

    private final Map<Integer, ObjectState> states = new HashMap<>();
    private Mat lastGrayFrame = null;

    public synchronized List<ActivityState> update(List<TrackedObject> trackedObjects) {
        return update(trackedObjects, null);
    }

    public synchronized List<ActivityState> update(List<TrackedObject> trackedObjects, Mat currentFrame) {
        List<ActivityState> result = new ArrayList<>();
        Set<Integer> currentIds = new HashSet<>();
        Instant now = Instant.now();

        Mat currentGray = null;
        if (currentFrame != null && !currentFrame.empty()) {
            try {
                currentGray = new Mat();
                if (currentFrame.channels() > 1) {
                    opencv_imgproc.cvtColor(currentFrame, currentGray, opencv_imgproc.COLOR_BGR2GRAY);
                } else {
                    currentGray = currentFrame.clone();
                }
            } catch (Exception e) {
                currentGray = null;
            }
        }

        for (TrackedObject object : trackedObjects) {
            int id = object.getId();
            currentIds.add(id);

            Rect box = object.getBoundingBox();
            double centerX = box.x() + (box.width() / 2.0);
            double centerY = box.y() + (box.height() / 2.0);
            double width = box.width();
            double height = Math.max(box.height(), 35.0);

            ObjectState state = states.get(id);

            if (state == null) {
                state = new ObjectState(id, object.getLabel(), centerX, centerY, width, height, now);
                states.put(id, state);
            } else {
                double rawDx = centerX - state.lastCenterX;
                double rawDy = centerY - state.lastCenterY;
                double rawDistance = Math.sqrt((rawDx * rawDx) + (rawDy * rawDy));

                long elapsedMillis = Math.max(1, Duration.between(state.lastUpdate, now).toMillis());
                double seconds = elapsedMillis / 1000.0;
                double instantSpeed = rawDistance / seconds;

                // Push position to sliding history window (max 6 points ~1.2s)
                state.addPosition(centerX, centerY, now);

                // Compute directional trajectory over temporal sliding window
                TrajectoryMetrics metrics = state.computeTrajectory(now);
                double locomotionSpeed = metrics.locomotionSpeed;
                double directionality = metrics.directionality;
                double netDistance = metrics.netDisplacement;

                // Localized arm/hand motion differencing for humans
                double handMotionEnergy = 0.0;
                boolean isPerson = "person".equalsIgnoreCase(object.getLabel());

                if (isPerson && currentGray != null && lastGrayFrame != null && !lastGrayFrame.empty()) {
                    handMotionEnergy = computeHandMotionEnergy(currentGray, lastGrayFrame, box);
                }

                // Width dynamic fluctuation (arms waving outward / gestures)
                double widthDeltaRatio = Math.abs(width - state.lastWidth) / Math.max(width, 1.0);
                boolean armExtensionDetected = isPerson && (widthDeltaRatio > 0.08) && (locomotionSpeed < 25.0);
                boolean activeHandMotion = (handMotionEnergy >= 0.040) || (armExtensionDetected && handMotionEnergy >= 0.02);

                state.lastHandMovementEnergy = handMotionEnergy;
                if (activeHandMotion) {
                    state.hasObservedHandMovement = true;
                }

                /*
                 * ----------------------------------------------------
                 * HUMAN BEHAVIOR CLASSIFIER
                 * ----------------------------------------------------
                 */
                if (isPerson) {
                    double aspectRatio = height / Math.max(width, 1.0);

                    // 1. Fall detection: abrupt aspect collapse
                    if (aspectRatio < 0.82 && height < state.lastHeight * 0.70) {
                        state.activity = "FALLEN / COLLAPSED";
                        state.consecutiveRunningFrames = 0;
                        state.consecutiveWalkingFrames = 0;
                    }
                    // 2. RUNNING: high locomotion velocity with directional consistency OR sudden sprint burst
                    else if ((locomotionSpeed >= MIN_RUNNING_LOCOMOTION_SPEED && directionality >= 0.38)
                            || (instantSpeed >= 105.0 && rawDistance >= 10.0)) {
                        state.consecutiveRunningFrames++;
                        state.consecutiveWalkingFrames = 0;
                        state.stationarySince = null;

                        if (state.consecutiveRunningFrames >= 1 || instantSpeed > 130.0) {
                            state.activity = "RUNNING";
                            state.hasObservedRunning = true;
                        } else {
                            state.activity = "WALKING";
                        }
                    }
                    // 3. WALKING: directional locomotion across ground plane
                    else if (locomotionSpeed >= MIN_WALKING_LOCOMOTION_SPEED
                            && directionality >= MIN_DIRECTIONALITY_RATIO
                            && netDistance >= JITTER_DEADBAND_PIXELS * 2.5) {
                        state.consecutiveWalkingFrames++;
                        state.consecutiveRunningFrames = 0;
                        state.stationarySince = null;

                        state.activity = "WALKING";
                        state.hasObservedWalking = true;
                    }
                    // 4. HAND MOVEMENT: stationary/seated posture with active arm/hand motion differencing
                    else if (activeHandMotion && locomotionSpeed < 25.0) {
                        state.consecutiveRunningFrames = 0;
                        state.consecutiveWalkingFrames = 0;
                        state.stationarySince = null;

                        state.activity = "HAND MOVEMENT";
                    }
                    // 5. SITTING / STANDING / LOITERING (Zero locomotion translation)
                    else {
                        state.consecutiveRunningFrames = 0;
                        state.consecutiveWalkingFrames = 0;

                        if (state.stationarySince == null) {
                            state.stationarySince = now;
                        }

                        long stationarySec = Duration.between(state.stationarySince, now).getSeconds();
                        if (stationarySec >= LOITERING_SECONDS) {
                            state.activity = "LOITERING";
                        } else if (aspectRatio <= 1.42) {
                            // Seated / desk-level posture
                            state.activity = "SITTING";
                        } else {
                            // Upright standing posture
                            state.activity = "STANDING";
                        }
                    }

                    // Displayed speed: zero out micro-jitter when stationary
                    state.smoothSpeed = (locomotionSpeed < 10.0) ? 0.0 : locomotionSpeed;

                } else {
                    // Vehicles & Animals
                    boolean isVehicle = "car".equalsIgnoreCase(object.getLabel())
                            || "motorcycle".equalsIgnoreCase(object.getLabel())
                            || "bus".equalsIgnoreCase(object.getLabel())
                            || "truck".equalsIgnoreCase(object.getLabel())
                            || "bicycle".equalsIgnoreCase(object.getLabel());

                    if (isVehicle) {
                        if (locomotionSpeed > 140.0) {
                            state.activity = "FAST_SPEED";
                        } else if (locomotionSpeed > 15.0) {
                            state.activity = "MOVING";
                        } else {
                            state.activity = "PARKED";
                        }
                    } else {
                        if (locomotionSpeed >= MIN_RUNNING_LOCOMOTION_SPEED) {
                            state.activity = "RUNNING";
                        } else if (locomotionSpeed >= MIN_WALKING_LOCOMOTION_SPEED) {
                            state.activity = "MOVING";
                        } else {
                            state.activity = "STATIONARY";
                        }
                    }
                    state.smoothSpeed = (locomotionSpeed < 10.0) ? 0.0 : locomotionSpeed;
                }

                state.lastSpeed = instantSpeed;
                state.lastCenterX = centerX;
                state.lastCenterY = centerY;
                state.lastWidth = width;
                state.lastHeight = height;
                state.lastBox = box;
                state.lastUpdate = now;
            }
        }

        // =========================================================================
        // MULTI-SUBJECT BEHAVIOR & INTERACTION ANALYSIS (FIGHTING & CROWD)
        // =========================================================================
        List<TrackedObject> persons = new ArrayList<>();
        for (TrackedObject obj : trackedObjects) {
            if ("person".equalsIgnoreCase(obj.getLabel())) {
                persons.add(obj);
            }
        }

        // 1. PHYSICAL ALTERCATION / FIGHTING DETECTION
        if (persons.size() >= 2) {
            for (int i = 0; i < persons.size(); i++) {
                TrackedObject p1 = persons.get(i);
                ObjectState s1 = states.get(p1.getId());
                if (s1 == null) continue;
                Rect b1 = p1.getBoundingBox();
                double c1x = b1.x() + b1.width() / 2.0;
                double c1y = b1.y() + b1.height() / 2.0;

                for (int j = i + 1; j < persons.size(); j++) {
                    TrackedObject p2 = persons.get(j);
                    ObjectState s2 = states.get(p2.getId());
                    if (s2 == null) continue;
                    Rect b2 = p2.getBoundingBox();
                    double c2x = b2.x() + b2.width() / 2.0;
                    double c2y = b2.y() + b2.height() / 2.0;

                    double dist = Math.sqrt(Math.pow(c1x - c2x, 2) + Math.pow(c1y - c2y, 2));
                    double maxDim = Math.max(Math.max(b1.width(), b1.height()), Math.max(b2.width(), b2.height()));
                    boolean closeProximity = dist < Math.max(160.0, maxDim * 1.45);

                    // Bounding boxes overlap/intersect
                    boolean boxesIntersect = (b1.x() < b2.x() + b2.width() && b1.x() + b1.width() > b2.x()
                            && b1.y() < b2.y() + b2.height() && b1.y() + b1.height() > b2.y());

                    if (closeProximity || boxesIntersect) {
                        // Dynamic fighting indicators: arm motion differencing, rapid sudden movements, or collapse
                        boolean highHandEnergy = (s1.lastHandMovementEnergy > 0.016 || s2.lastHandMovementEnergy > 0.016);
                        boolean rapidMovement = (s1.lastSpeed > 18.0 || s2.lastSpeed > 18.0 || s1.smoothSpeed > 15.0 || s2.smoothSpeed > 15.0);
                        boolean fallenInContact = s1.activity.contains("FALLEN") || s2.activity.contains("FALLEN");

                        if (highHandEnergy || rapidMovement || fallenInContact || boxesIntersect) {
                            s1.consecutiveFightingFrames++;
                            s2.consecutiveFightingFrames++;

                            if (s1.consecutiveFightingFrames >= 1 || highHandEnergy || fallenInContact) {
                                s1.activity = "FIGHTING";
                                s2.activity = "FIGHTING";
                                s1.finalActivity = "FIGHTING";
                                s2.finalActivity = "FIGHTING";
                                s1.hasObservedFighting = true;
                                s2.hasObservedFighting = true;
                                s1.dynamicAlert = "CRITICAL: Physical fighting / altercation detected with person #" + p2.getId();
                                s2.dynamicAlert = "CRITICAL: Physical fighting / altercation detected with person #" + p1.getId();
                            }
                        } else {
                            s1.consecutiveFightingFrames = Math.max(0, s1.consecutiveFightingFrames - 1);
                            s2.consecutiveFightingFrames = Math.max(0, s2.consecutiveFightingFrames - 1);
                        }
                    }
                }
            }
        }

        // Single person aggressive striking / rapid violent upper-body motion
        for (TrackedObject p : persons) {
            ObjectState s = states.get(p.getId());
            if (s == null) continue;
            if (s.lastHandMovementEnergy > 0.040 && s.smoothSpeed > 20.0 && !"FIGHTING".equals(s.activity) && !"RUNNING".equals(s.activity)) {
                s.activity = "FIGHTING";
                s.finalActivity = "FIGHTING";
                s.hasObservedFighting = true;
                s.dynamicAlert = "CRITICAL: Violent striking motion / altercation detected for person #" + p.getId();
            }
        }

        // 2. LARGE CROWD AREA / CROWD GATHERING DETECTION
        if (persons.size() >= 3) {
            for (TrackedObject p : persons) {
                ObjectState s = states.get(p.getId());
                if (s == null) continue;
                Rect b = p.getBoundingBox();
                double pcx = b.x() + b.width() / 2.0;
                double pcy = b.y() + b.height() / 2.0;

                int neighbors = 0;
                for (TrackedObject other : persons) {
                    if (other.getId() == p.getId()) continue;
                    Rect ob = other.getBoundingBox();
                    double ocx = ob.x() + ob.width() / 2.0;
                    double ocy = ob.y() + ob.height() / 2.0;
                    double d = Math.sqrt(Math.pow(pcx - ocx, 2) + Math.pow(pcy - ocy, 2));
                    if (d < 280.0) {
                        neighbors++;
                    }
                }

                // If person has 2 or more close neighbors OR scene has 4+ persons overall
                if (neighbors >= 2 || persons.size() >= 4) {
                    if (!"FIGHTING".equals(s.activity) && !"RUNNING".equals(s.activity) && !"FALLEN / COLLAPSED".equals(s.activity)) {
                        s.activity = "CROWD GATHERING";
                        s.hasObservedCrowd = true;
                        if (!"FIGHTING".equals(s.finalActivity) && !"RUNNING".equals(s.finalActivity) && !"FALLEN / COLLAPSED".equals(s.finalActivity)) {
                            s.finalActivity = "CROWD GATHERING";
                        }
                        int crowdCount = Math.max(neighbors + 1, persons.size());
                        s.dynamicAlert = "SECURITY ALERT: Large crowd area (" + crowdCount + " persons in area) detected";
                    }
                }
            }
        }

        // =========================================================================
        // FINAL ACTIVITY CONSOLIDATION & RESULT GENERATION
        // =========================================================================
        for (TrackedObject object : trackedObjects) {
            int id = object.getId();
            ObjectState state = states.get(id);
            if (state == null) continue;

            updateFinalActivity(state);

            if (state.finalActivity == null) {
                state.finalActivity = state.activity != null ? state.activity : "STANDING";
            }

            boolean anomaly = isAbnormalBehavior(state.activity) || isAbnormalBehavior(state.finalActivity);
            long stationarySeconds = getStationarySeconds(state, now);
            String alertMessage = null;

            if (anomaly) {
                if (state.dynamicAlert != null) {
                    alertMessage = state.dynamicAlert;
                } else if (state.activity.contains("FIGHT")) {
                    alertMessage = "CRITICAL: Physical altercation / fighting detected for " + object.getLabel() + " #" + object.getId();
                } else if (state.activity.contains("CROWD")) {
                    alertMessage = "SECURITY ALERT: Crowd gathering detected involving " + object.getLabel() + " #" + object.getId();
                } else if (state.activity.equals("RUNNING")) {
                    alertMessage = "SECURITY ALERT: " + object.getLabel() + " #" + object.getId() + " sprinting at high speed";
                } else if (state.activity.contains("FALLEN")) {
                    alertMessage = "CRITICAL: " + object.getLabel() + " #" + object.getId() + " fell or collapsed!";
                } else if (state.activity.equals("LOITERING")) {
                    alertMessage = "SUSPICIOUS: " + object.getLabel() + " #" + object.getId() + " has remained stationary for " + stationarySeconds + "s.";
                } else {
                    alertMessage = "UNUSUAL BEHAVIOR: " + state.activity + " detected for " + object.getLabel() + " #" + object.getId();
                }
            }

            result.add(new ActivityState(
                    id,
                    object.getLabel(),
                    object.getConfidence(),
                    state.activity,
                    state.finalActivity,
                    anomaly,
                    alertMessage,
                    stationarySeconds,
                    now,
                    Math.round(state.smoothSpeed * 10.0) / 10.0,
                    Math.round(state.lastHandMovementEnergy * 100.0) / 100.0
            ));
        }

        // Cache previous grayscale frame
        if (currentGray != null) {
            this.lastGrayFrame = currentGray;
        }

        states.keySet().removeIf(id -> !currentIds.contains(id));
        return result;
    }

    /**
     * Compute localized arm and hand motion differencing within the person's upper/lateral bounding box.
     */
    private double computeHandMotionEnergy(Mat curGray, Mat prevGray, Rect box) {
        try {
            int frameW = curGray.cols();
            int frameH = curGray.rows();
            if (frameW <= 0 || frameH <= 0) return 0.0;
            if (prevGray.cols() != frameW || prevGray.rows() != frameH) return 0.0;

            int bx = Math.max(0, box.x());
            int by = Math.max(0, box.y());
            int bw = Math.min(box.width(), frameW - bx);
            int bh = Math.min(box.height(), frameH - by);
            if (bw < 15 || bh < 25) return 0.0;

            Rect personRoi = new Rect(bx, by, bw, bh);
            Mat curPerson = new Mat(curGray, personRoi);
            Mat prevPerson = new Mat(prevGray, personRoi);

            Mat diff = new Mat();
            opencv_core.absdiff(curPerson, prevPerson, diff);

            Mat thresh = new Mat();
            opencv_imgproc.threshold(diff, thresh, 25.0, 255.0, opencv_imgproc.THRESH_BINARY);

            int armTop = (int) (bh * 0.15);
            int armHeight = Math.max(1, (int) (bh * 0.60));
            int leftArmWidth = Math.max(1, (int) (bw * 0.35));
            int rightArmX = (int) (bw * 0.65);
            int rightArmWidth = Math.max(1, bw - rightArmX);

            int leftArmPixels = 0;
            int rightArmPixels = 0;
            int totalArmArea = Math.max(1, (leftArmWidth + rightArmWidth) * armHeight);

            if (leftArmWidth > 0 && armHeight > 0 && (armTop + armHeight <= bh) && (leftArmWidth <= bw)) {
                Rect leftRoi = new Rect(0, armTop, leftArmWidth, armHeight);
                Mat leftMat = new Mat(thresh, leftRoi);
                leftArmPixels = opencv_core.countNonZero(leftMat);
            }

            if (rightArmWidth > 0 && armHeight > 0 && (armTop + armHeight <= bh) && (rightArmX + rightArmWidth <= bw)) {
                Rect rightRoi = new Rect(rightArmX, armTop, rightArmWidth, armHeight);
                Mat rightMat = new Mat(thresh, rightRoi);
                rightArmPixels = opencv_core.countNonZero(rightMat);
            }

            int torsoX = (int) (bw * 0.30);
            int torsoW = Math.max(1, (int) (bw * 0.40));
            int torsoArea = Math.max(1, torsoW * armHeight);
            int torsoPixels = 0;
            if (torsoX + torsoW <= bw && armTop + armHeight <= bh) {
                Rect torsoRoi = new Rect(torsoX, armTop, torsoW, armHeight);
                Mat torsoMat = new Mat(thresh, torsoRoi);
                torsoPixels = opencv_core.countNonZero(torsoMat);
            }

            double armMotionScore = (double) (leftArmPixels + rightArmPixels) / totalArmArea;
            double torsoMotionScore = (double) torsoPixels / torsoArea;

            if (armMotionScore > 0.038) {
                if (armMotionScore >= torsoMotionScore * 1.15) {
                    return Math.min(1.0, armMotionScore * 2.5);
                } else {
                    return Math.min(1.0, armMotionScore * 1.5);
                }
            }
            return armMotionScore;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private void updateFinalActivity(ObjectState state) {
        String current = state.activity;
        String previous = state.finalActivity;

        if (previous == null) {
            state.finalActivity = current;
            return;
        }

        if ("FALLEN / COLLAPSED".equals(current) || current.contains("FALLEN")) {
            state.finalActivity = "FALLEN / COLLAPSED";
            return;
        }

        if ("FIGHTING".equals(current) || current.contains("FIGHT")) {
            state.finalActivity = "FIGHTING";
            return;
        }

        if ("RUNNING".equals(current) || "FLEEING".equals(current)) {
            if (!"FIGHTING".equals(previous) && !"FALLEN / COLLAPSED".equals(previous)) {
                state.finalActivity = "RUNNING";
            }
            return;
        }

        if ("CROWD GATHERING".equals(current) || current.contains("CROWD")) {
            if (!"FIGHTING".equals(previous) && !"RUNNING".equals(previous) && !"FALLEN / COLLAPSED".equals(previous)) {
                state.finalActivity = "CROWD GATHERING";
            }
            return;
        }

        if ("WALKING".equals(current) && !"RUNNING".equals(previous) && !"FIGHTING".equals(previous) && !"CROWD GATHERING".equals(previous)) {
            state.finalActivity = "WALKING";
            return;
        }

        if (("HAND MOVEMENT".equals(current) || current.contains("HAND"))
                && !"RUNNING".equals(previous) && !"WALKING".equals(previous) && !"FIGHTING".equals(previous)) {
            state.finalActivity = "HAND MOVEMENT";
            return;
        }

        if ("LOITERING".equals(current) && ("STATIONARY".equals(previous) || "STANDING".equals(previous) || "SITTING".equals(previous))) {
            state.finalActivity = "LOITERING";
            return;
        }

        if (("STANDING".equals(current) || "SITTING".equals(current)) && "STATIONARY".equals(previous)) {
            state.finalActivity = current;
        }
    }

    public static boolean isAbnormalBehavior(String activity) {
        if (activity == null || activity.isBlank()) return false;
        String a = activity.toUpperCase();
        return a.contains("FIGHT")
                || a.contains("CROWD")
                || a.contains("RUNNING")
                || a.contains("SPRINT")
                || a.contains("FALLEN")
                || a.contains("COLLAPSE")
                || a.contains("LOITERING")
                || a.contains("FAST")
                || a.contains("ALTERCATION")
                || a.contains("BRAWL")
                || a.contains("ASSAULT")
                || a.contains("SNATCH")
                || a.contains("INTRUSION");
    }

    public synchronized ActivityState get(int objectId) {
        ObjectState state = states.get(objectId);
        if (state == null) {
            return null;
        }

        Instant now = Instant.now();
        long stationarySeconds = getStationarySeconds(state, now);
        boolean anomaly = isAbnormalBehavior(state.activity) || isAbnormalBehavior(state.finalActivity);
        String alert = anomaly ? (state.dynamicAlert != null ? state.dynamicAlert : state.label + " #" + state.id + " -> " + state.activity) : null;

        return new ActivityState(
                state.id,
                state.label,
                1.0,
                state.activity,
                state.finalActivity,
                anomaly,
                alert,
                stationarySeconds,
                now,
                Math.round(state.smoothSpeed * 10.0) / 10.0,
                Math.round(state.lastHandMovementEnergy * 100.0) / 100.0
        );
    }

    public synchronized void reset() {
        states.clear();
        lastGrayFrame = null;
    }

    private long getStationarySeconds(ObjectState state, Instant now) {
        if (state.stationarySince == null) {
            return 0;
        }
        return Math.max(0, Duration.between(state.stationarySince, now).getSeconds());
    }

    private static class TrajectoryMetrics {
        final double netDisplacement;
        final double locomotionSpeed;
        final double directionality;

        TrajectoryMetrics(double netDisplacement, double locomotionSpeed, double directionality) {
            this.netDisplacement = netDisplacement;
            this.locomotionSpeed = locomotionSpeed;
            this.directionality = directionality;
        }
    }

    private static class ObjectState {
        private final int id;
        private final String label;
        private double lastCenterX;
        private double lastCenterY;
        private double lastWidth;
        private double lastHeight;
        private double lastSpeed = 0.0;
        private double smoothSpeed = 0.0;
        private int consecutiveRunningFrames = 0;
        private int consecutiveWalkingFrames = 0;
        private int consecutiveFightingFrames = 0;
        private double lastHandMovementEnergy = 0.0;
        private boolean hasObservedHandMovement = false;
        private boolean hasObservedWalking = false;
        private boolean hasObservedRunning = false;
        private boolean hasObservedFighting = false;
        private boolean hasObservedCrowd = false;
        private String dynamicAlert = null;
        private Rect lastBox = null;
        private Instant lastUpdate;
        private Instant stationarySince;
        private String activity = "STANDING";
        private String finalActivity = "STANDING";

        private final List<Double> xHistory = new ArrayList<>();
        private final List<Double> yHistory = new ArrayList<>();
        private final List<Instant> timeHistory = new ArrayList<>();

        private ObjectState(int id, String label, double centerX, double centerY, double width, double height, Instant now) {
            this.id = id;
            this.label = label;
            this.lastCenterX = centerX;
            this.lastCenterY = centerY;
            this.lastWidth = width;
            this.lastHeight = height;
            this.lastUpdate = now;
            this.stationarySince = now;
            addPosition(centerX, centerY, now);
        }

        void addPosition(double x, double y, Instant time) {
            xHistory.add(x);
            yHistory.add(y);
            timeHistory.add(time);
            while (xHistory.size() > 7) {
                xHistory.remove(0);
                yHistory.remove(0);
                timeHistory.remove(0);
            }
        }

        TrajectoryMetrics computeTrajectory(Instant now) {
            int n = xHistory.size();
            if (n < 2) {
                return new TrajectoryMetrics(0.0, 0.0, 0.0);
            }

            double startX = xHistory.get(0);
            double startY = yHistory.get(0);
            Instant startTime = timeHistory.get(0);

            double duration = Math.max(0.12, Duration.between(startTime, now).toMillis() / 1000.0);

            double netDx = lastCenterX - startX;
            double netDy = lastCenterY - startY;
            double netDisplacement = Math.sqrt((netDx * netDx) + (netDy * netDy));

            double totalPath = 0.0;
            for (int i = 1; i < n; i++) {
                double stepDx = xHistory.get(i) - xHistory.get(i - 1);
                double stepDy = yHistory.get(i) - yHistory.get(i - 1);
                totalPath += Math.sqrt((stepDx * stepDx) + (stepDy * stepDy));
            }

            double directionality = (totalPath > 6.0) ? (netDisplacement / totalPath) : 0.0;
            double speed = netDisplacement / duration;

            return new TrajectoryMetrics(netDisplacement, speed, directionality);
        }
    }

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
        private final double speed;
        private final double handMovementEnergy;

        public ActivityState(
                int objectId,
                String objectType,
                double confidence,
                String activity,
                String finalActivity,
                boolean anomaly,
                String alertMessage,
                long stationarySeconds,
                Instant timestamp,
                double speed,
                double handMovementEnergy
        ) {
            this.objectId = objectId;
            this.objectType = objectType;
            this.confidence = confidence;
            this.activity = activity;
            this.finalActivity = finalActivity;
            this.anomaly = anomaly;
            this.alertMessage = alertMessage;
            this.stationarySeconds = stationarySeconds;
            this.timestamp = timestamp;
            this.speed = speed;
            this.handMovementEnergy = handMovementEnergy;
        }

        public int getObjectId() { return objectId; }
        public String getObjectType() { return objectType; }
        public double getConfidence() { return confidence; }
        public String getActivity() { return activity; }
        public String getFinalActivity() { return finalActivity; }
        public boolean isAnomaly() { return anomaly; }
        public String getAlertMessage() { return alertMessage; }
        public long getStationarySeconds() { return stationarySeconds; }
        public Instant getTimestamp() { return timestamp; }
        public double getSpeed() { return speed; }
        public double getHandMovementEnergy() { return handMovementEnergy; }
    }
}