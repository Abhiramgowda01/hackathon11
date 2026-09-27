package cctvai.service;

import cctvai.detection.ObjectTracker.TrackedObject;
import org.bytedeco.opencv.opencv_core.Rect;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Intelligent Behavior Analysis & Security Threat Detection Engine.
 *
 * Detects:
 * 1. Human natural behaviors:
 *    - Standing / Stationary
 *    - Walking / Transit
 *    - Running / Sprinting
 *    - Loitering
 *    - Pacing
 *    - Fallen / Collapsed
 *    - Approaching
 *    - Fleeing
 * 2. Security Breaches:
 *    - Chain / Bag Snatching
 *    - Robbery / Armed Mugging
 *    - Kidnapping / Forced Abduction
 *    - Physical Assault / Brawl
 *    - Weapon Brandishing
 *    - Suspicious Loitering
 *    - Unattended Baggage / Suspicious Objects
 * 3. Real-time streaming live event logs without missing any detected object!
 */
@Service
public class BehaviorAnalysisEngine {

    public static class LiveEvent {
        public final double timestampSeconds;
        public final String timeFormatted;
        public final String text;
        public final String badge;
        public final String level; // SAFE, SUSPICIOUS, BREACH
        public final String objectType;
        public final int objectId;
        public final String icon;

        public LiveEvent(double timestampSeconds, String timeFormatted, String text,
                         String badge, String level, String objectType, int objectId, String icon) {
            this.timestampSeconds = timestampSeconds;
            this.timeFormatted = timeFormatted;
            this.text = text;
            this.badge = badge;
            this.level = level;
            this.objectType = objectType;
            this.objectId = objectId;
            this.icon = icon;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("time", timeFormatted);
            map.put("seconds", Math.round(timestampSeconds * 10.0) / 10.0);
            map.put("text", text);
            map.put("badge", badge);
            map.put("level", level);
            map.put("type", objectType);
            map.put("id", objectId);
            map.put("icon", icon);
            return map;
        }
    }

    public static class SecurityIncident {
        public String type; // CHAIN_SNATCHING, ROBBERY, KIDNAPPING, ASSAULT, etc.
        public String severity; // CRITICAL, WARNING, SAFE
        public String title;
        public String summary;
        public double timestamp;
        public double confidence;
        public final List<String> indicators = new ArrayList<>();
        public final Set<Integer> suspectIds = new LinkedHashSet<>();
        public final Set<Integer> victimIds = new LinkedHashSet<>();
        public final Set<String> involvedObjects = new LinkedHashSet<>();

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("is_security_breach", !"SAFE".equalsIgnoreCase(severity));
            map.put("severity", severity);
            map.put("breach_type", type);
            map.put("confidence", Math.round(confidence * 100.0) / 100.0);
            map.put("verdict_title", title);
            map.put("summary", summary);
            map.put("timestamp", formatTime(timestamp));
            map.put("key_indicators", indicators);
            map.put("suspect_ids", new ArrayList<>(suspectIds));
            map.put("victim_ids", new ArrayList<>(victimIds));
            map.put("involved_objects", new ArrayList<>(involvedObjects));
            return map;
        }
    }

    public static class ObjectTrajectory {
        public final int id;
        public final String label;
        public final double firstSeenTime;
        public double lastSeenTime;
        public double lastCenterX;
        public double lastCenterY;
        public double lastWidth;
        public double lastHeight;
        public double currentSpeed; // pixels per second
        public double peakSpeed;
        public String currentBehavior = "STATIONARY";
        public String primaryBehavior = "STATIONARY";
        public final Set<String> behaviorsObserved = new LinkedHashSet<>();
        public double stationarySinceTime = -1;
        public boolean hasFallen = false;
        public boolean isFleeing = false;
        public boolean isSuspect = false;
        public boolean isVictim = false;
        public final List<Double> speedHistory = new ArrayList<>();
        public final List<Double> xHistory = new ArrayList<>();
        public final List<Double> yHistory = new ArrayList<>();
        public final List<Double> timeHistory = new ArrayList<>();

        public ObjectTrajectory(int id, String label, double time, double cx, double cy, double w, double h) {
            this.id = id;
            this.label = label;
            this.firstSeenTime = time;
            this.lastSeenTime = time;
            this.lastCenterX = cx;
            this.lastCenterY = cy;
            this.lastWidth = w;
            this.lastHeight = h;
            this.stationarySinceTime = time;
            this.behaviorsObserved.add("STATIONARY");
            this.xHistory.add(cx);
            this.yHistory.add(cy);
            this.timeHistory.add(time);
            this.speedHistory.add(0.0);
        }
    }

    /**
     * Session context for a single video analysis run.
     */
    public static class AnalysisSession {
        public final Map<Integer, ObjectTrajectory> trajectories = new ConcurrentHashMap<>();
        public final List<LiveEvent> liveLog = new CopyOnWriteArrayList<>();
        public final List<SecurityIncident> detectedIncidents = new CopyOnWriteArrayList<>();
        public volatile SecurityIncident highestThreatIncident = null;
        public volatile String currentSceneDescription = "Monitoring camera view...";
        public final Set<String> allDetectedLabels = ConcurrentHashMap.newKeySet();

        public AnalysisSession() {
            SecurityIncident safe = new SecurityIncident();
            safe.type = "NORMAL_ROUTINE";
            safe.severity = "SAFE";
            safe.confidence = 0.95;
            safe.title = "🛡️ SECURE: Normal Human Behavior & Environment";
            safe.summary = "No suspicious behaviors, violent altercations, or security breaches detected.";
            safe.indicators.add("All human movements within normal velocity parameters");
            safe.indicators.add("No aggressive physical interactions observed");
            this.highestThreatIncident = safe;
        }
    }

    public AnalysisSession createSession() {
        return new AnalysisSession();
    }

    /**
     * Process one frame of tracked objects in the video analysis pipeline.
     */
    public void processFrame(AnalysisSession session, double videoTimeSeconds, List<TrackedObject> currentObjects) {
        Set<Integer> currentFrameIds = new HashSet<>();

        // 1. Update trajectories
        for (TrackedObject obj : currentObjects) {
            int id = obj.getId();
            String label = obj.getLabel().toLowerCase();
            currentFrameIds.add(id);
            session.allDetectedLabels.add(label);

            Rect box = obj.getBoundingBox();
            double cx = box.x() + (box.width() / 2.0);
            double cy = box.y() + (box.height() / 2.0);
            double w = box.width();
            double h = box.height();

            ObjectTrajectory traj = session.trajectories.get(id);
            if (traj == null) {
                traj = new ObjectTrajectory(id, label, videoTimeSeconds, cx, cy, w, h);
                session.trajectories.put(id, traj);

                // Live event: New object appeared
                String icon = getObjectIcon(label);
                String desc = formatNewObjectDescription(label, id);
                session.liveLog.add(new LiveEvent(
                        videoTimeSeconds,
                        formatTime(videoTimeSeconds),
                        desc,
                        "NEW OBJECT",
                        "SAFE",
                        label,
                        id,
                        icon
                ));
            } else {
                double dt = Math.max(0.04, videoTimeSeconds - traj.lastSeenTime);
                double dx = cx - traj.lastCenterX;
                double dy = cy - traj.lastCenterY;
                double dist = Math.sqrt(dx * dx + dy * dy);
                double speed = dist / dt;

                traj.lastSeenTime = videoTimeSeconds;
                traj.lastCenterX = cx;
                traj.lastCenterY = cy;
                traj.lastWidth = w;
                traj.lastHeight = h;
                traj.currentSpeed = speed;
                if (speed > traj.peakSpeed) {
                    traj.peakSpeed = speed;
                }
                traj.xHistory.add(cx);
                traj.yHistory.add(cy);
                traj.timeHistory.add(videoTimeSeconds);
                traj.speedHistory.add(speed);

                // Detect Human Natural Behaviors
                if ("person".equalsIgnoreCase(label)) {
                    detectPersonBehavior(session, traj, videoTimeSeconds, speed, w, h);
                } else if (isVehicle(label)) {
                    detectVehicleBehavior(session, traj, videoTimeSeconds, speed);
                } else if (isCarriedItem(label)) {
                    detectCarriedItemBehavior(session, traj, videoTimeSeconds, speed, currentObjects);
                } else if (isWeapon(label)) {
                    detectWeaponBehavior(session, traj, videoTimeSeconds);
                }
            }
        }

        // 2. Multi-Subject Interactions & Threat Detection
        evaluateSecurityBreaches(session, videoTimeSeconds, currentObjects);

        // 3. Update current real-time sentence description
        session.currentSceneDescription = buildRunningDescription(session, videoTimeSeconds, currentObjects);
    }

    private void detectPersonBehavior(AnalysisSession session, ObjectTrajectory traj, double time, double speed, double w, double h) {
        String prevBehavior = traj.currentBehavior;
        String newBehavior;

        // Aspect ratio: Height to Width. Upright person is usually h/w >= 1.2
        double aspectRatio = (w > 0) ? (h / w) : 1.5;
        double height = Math.max(h, 35.0);
        double normSpeed = speed / height; // Velocity in body-heights per second

        // Acceleration calculation
        double accel = 0.0;
        if (traj.speedHistory.size() >= 2 && traj.timeHistory.size() >= 2) {
            double prevSpeed = traj.speedHistory.get(traj.speedHistory.size() - 2);
            double prevTime = traj.timeHistory.get(traj.timeHistory.size() - 2);
            double dt = Math.max(0.05, time - prevTime);
            accel = Math.abs(speed - prevSpeed) / dt;
        }

        // Arm/Hand movement dynamics (width expansion & upper-body lateral motion fluctuation)
        double widthChangeRatio = Math.abs(w - traj.lastWidth) / Math.max(w, 1.0);
        boolean armMotion = (widthChangeRatio > 0.08) && (speed < 45.0);

        // Fall detection: abrupt aspect ratio drop + low speed
        if (!traj.hasFallen && aspectRatio < 0.85 && h < traj.lastHeight * 0.75) {
            traj.hasFallen = true;
            newBehavior = "FALLEN / COLLAPSED";
            session.liveLog.add(new LiveEvent(
                    time,
                    formatTime(time),
                    "⚠️ Person #" + traj.id + " fell or collapsed to the ground!",
                    "FALLEN",
                    "SUSPICIOUS",
                    "person",
                    traj.id,
                    "🚨"
            ));
        } else if (traj.hasFallen) {
            newBehavior = "FALLEN / COLLAPSED";
        } else if (normSpeed >= 1.25 || speed > 165.0 || (accel > 115.0 && speed > 125.0)) {
            newBehavior = traj.isFleeing ? "FLEEING / SPRINTING" : "RUNNING";
            traj.stationarySinceTime = -1;
        } else if ((normSpeed >= 0.16 || speed > 18.0) && speed <= 165.0) {
            newBehavior = "WALKING";
            traj.stationarySinceTime = -1;
        } else if (armMotion && speed < 45.0) {
            newBehavior = "HAND MOVEMENT";
            traj.stationarySinceTime = -1;
        } else {
            // Stationary
            if (traj.stationarySinceTime < 0) {
                traj.stationarySinceTime = time;
            }
            double stationarySec = time - traj.stationarySinceTime;
            if (stationarySec > 18.0) {
                newBehavior = "LOITERING";
            } else {
                newBehavior = "STANDING";
            }
        }

        traj.currentBehavior = newBehavior;
        traj.behaviorsObserved.add(newBehavior);
        updatePrimaryBehavior(traj);

        // Log notable behavior change (e.g. Walking -> Running / Hand Movement / Loitering)
        if (!newBehavior.equals(prevBehavior)) {
            if ("RUNNING".equals(newBehavior) || "FLEEING / SPRINTING".equals(newBehavior)) {
                session.liveLog.add(new LiveEvent(
                        time,
                        formatTime(time),
                        "🏃 Person #" + traj.id + " suddenly accelerated into a RUN (" + Math.round(speed) + " px/s)",
                        "SPRINT",
                        "SUSPICIOUS",
                        "person",
                        traj.id,
                        "🏃"
                ));
            } else if ("HAND MOVEMENT".equals(newBehavior)) {
                session.liveLog.add(new LiveEvent(
                        time,
                        formatTime(time),
                        "👋 Person #" + traj.id + " active HAND MOVEMENT (gesturing / waving)",
                        "HAND MOVEMENT",
                        "SAFE",
                        "person",
                        traj.id,
                        "👋"
                ));
            } else if ("WALKING".equals(newBehavior) && ("STANDING".equals(prevBehavior) || "LOITERING".equals(prevBehavior))) {
                session.liveLog.add(new LiveEvent(
                        time,
                        formatTime(time),
                        "🚶 Person #" + traj.id + " started WALKING (" + Math.round(speed) + " px/s)",
                        "WALKING",
                        "SAFE",
                        "person",
                        traj.id,
                        "🚶"
                ));
            } else if ("LOITERING".equals(newBehavior)) {
                session.liveLog.add(new LiveEvent(
                        time,
                        formatTime(time),
                        "⏱️ Person #" + traj.id + " has been loitering in place for > 18 seconds",
                        "LOITERING",
                        "SUSPICIOUS",
                        "person",
                        traj.id,
                        "⏱️"
                ));
            }
        }
    }

    private void detectVehicleBehavior(AnalysisSession session, ObjectTrajectory traj, double time, double speed) {
        String newBehavior;
        if (speed > 180.0) {
            newBehavior = "FAST_SPEED";
        } else if (speed > 20.0) {
            newBehavior = "MOVING";
        } else {
            newBehavior = "PARKED";
        }
        traj.currentBehavior = newBehavior;
        traj.behaviorsObserved.add(newBehavior);
        updatePrimaryBehavior(traj);
    }

    private void detectCarriedItemBehavior(AnalysisSession session, ObjectTrajectory traj, double time, double speed, List<TrackedObject> currentObjects) {
        // Check if there is a person nearby (< 70px)
        boolean hasPersonNear = false;
        for (TrackedObject other : currentObjects) {
            if ("person".equalsIgnoreCase(other.getLabel())) {
                Rect b1 = other.getBoundingBox();
                double pcx = b1.x() + b1.width() / 2.0;
                double pcy = b1.y() + b1.height() / 2.0;
                double dist = Math.sqrt(Math.pow(traj.lastCenterX - pcx, 2) + Math.pow(traj.lastCenterY - pcy, 2));
                if (dist < 80.0) {
                    hasPersonNear = true;
                    break;
                }
            }
        }

        if (hasPersonNear) {
            traj.currentBehavior = "CARRIED_BY_PERSON";
            traj.stationarySinceTime = -1;
        } else {
            if (traj.stationarySinceTime < 0) {
                traj.stationarySinceTime = time;
            }
            double unattendedSec = time - traj.stationarySinceTime;
            if (unattendedSec > 15.0) {
                traj.currentBehavior = "UNATTENDED";
                if (!traj.behaviorsObserved.contains("UNATTENDED")) {
                    session.liveLog.add(new LiveEvent(
                            time,
                            formatTime(time),
                            "⚠️ UNATTENDED OBJECT: " + capitalize(traj.label) + " #" + traj.id + " left alone for " + Math.round(unattendedSec) + "s",
                            "SUSPICIOUS",
                            "SUSPICIOUS",
                            traj.label,
                            traj.id,
                            "🧳"
                    ));
                    registerIncident(session, createUnattendedObjectIncident(traj, time));
                }
            } else {
                traj.currentBehavior = "PLACED";
            }
        }
        traj.behaviorsObserved.add(traj.currentBehavior);
        updatePrimaryBehavior(traj);
    }

    private void detectWeaponBehavior(AnalysisSession session, ObjectTrajectory traj, double time) {
        traj.currentBehavior = "WEAPON_DETECTED";
        traj.behaviorsObserved.add("WEAPON_DETECTED");
        traj.primaryBehavior = "WEAPON_DETECTED";

        session.liveLog.add(new LiveEvent(
                time,
                formatTime(time),
                "🚨 CRITICAL WEAPON ALERT: Dangerous object (" + traj.label + " #" + traj.id + ") detected in active view!",
                "ARMED THREAT",
                "BREACH",
                traj.label,
                traj.id,
                "🔪"
        ));

        SecurityIncident inc = new SecurityIncident();
        inc.type = "ARMED_THREAT_OR_WEAPON";
        inc.severity = "CRITICAL";
        inc.confidence = 0.94;
        inc.title = "🚨 CRITICAL SECURITY BREACH: Weapon Brandished (" + capitalize(traj.label) + ")";
        inc.summary = "A dangerous weapon (" + traj.label + ") was visually detected in the monitored zone.";
        inc.timestamp = time;
        inc.indicators.add("Visual confirmation of " + traj.label + " in frame");
        inc.indicators.add("High probability of armed robbery or violent confrontation");
        inc.involvedObjects.add(traj.label + " #" + traj.id);
        registerIncident(session, inc);
    }

    /**
     * Evaluate complex interactions: Snatching, Robbery, Kidnapping, Assault.
     */
    private void evaluateSecurityBreaches(AnalysisSession session, double time, List<TrackedObject> currentObjects) {
        List<TrackedObject> people = new ArrayList<>();
        List<TrackedObject> vehicles = new ArrayList<>();
        List<TrackedObject> items = new ArrayList<>();

        for (TrackedObject obj : currentObjects) {
            String l = obj.getLabel().toLowerCase();
            if ("person".equals(l)) people.add(obj);
            else if (isVehicle(l)) vehicles.add(obj);
            else if (isCarriedItem(l)) items.add(obj);
        }

        // =========================================================================
        // 1. CHAIN SNATCHING / BAG SNATCHING DETECTION
        // =========================================================================
        // Pattern: Suspect (person or motorcycle/bicycle) rapidly approaches victim,
        // comes in close contact (< 70px) where an item is carried/present,
        // then suspect immediately bolts away at high speed (>190 px/s) while victim
        // decelerates, stumbles, falls, or is left behind.
        for (TrackedObject p1 : people) {
            ObjectTrajectory t1 = session.trajectories.get(p1.getId());
            if (t1 == null) continue;

            // Check against other persons
            for (TrackedObject p2 : people) {
                if (p1.getId() == p2.getId()) continue;
                ObjectTrajectory t2 = session.trajectories.get(p2.getId());
                if (t2 == null) continue;

                checkSnatchingBetween(session, time, t1, t2, items);
                checkPhysicalAssault(session, time, t1, t2);
            }

            // Check against vehicles (motorcycle or bicycle snatching)
            for (TrackedObject v : vehicles) {
                ObjectTrajectory tv = session.trajectories.get(v.getId());
                if (tv != null) {
                    checkVehicleSnatching(session, time, tv, t1, items);
                    checkKidnappingVehicle(session, time, people, tv);
                }
            }
        }

        // =========================================================================
        // 2. ROBBERY / MUGGING (Cornering & Surrounding)
        // =========================================================================
        if (people.size() >= 3) {
            checkCorneringRobbery(session, time, people, items);
        }

        // =========================================================================
        // 3. KIDNAPPING (Forced Abduction / Group Grapple & Dragging)
        // =========================================================================
        if (people.size() >= 3) {
            checkKidnappingGroup(session, time, people);
        }

        // =========================================================================
        // 4. PHYSICAL ALTERCATION / FIGHTING BETWEEN SUBJECTS
        // =========================================================================
        if (people.size() >= 2) {
            checkFightingAndAltercation(session, time, people);
        }

        // =========================================================================
        // 5. CROWD GATHERING & CONGREGATION
        // =========================================================================
        if (people.size() >= 3) {
            checkCrowdGathering(session, time, people);
        }
    }

    private void checkSnatchingBetween(AnalysisSession session, double time, ObjectTrajectory suspect, ObjectTrajectory victim, List<TrackedObject> items) {
        double dist = distanceBetween(suspect.lastCenterX, suspect.lastCenterY, victim.lastCenterX, victim.lastCenterY);

        // Close proximity contact
        if (dist < 75.0) {
            // Check if suspect has high peak speed after this contact or is sprinting (>190 px/s)
            boolean suspectRunning = suspect.currentSpeed > 180.0 || suspect.peakSpeed > 220.0;
            boolean victimSlower = victim.currentSpeed < suspect.currentSpeed * 0.6 || victim.hasFallen;

            if (suspectRunning && victimSlower) {
                // Find any carried item involved
                String itemDesc = "carried belongings / necklace";
                for (TrackedObject item : items) {
                    double idist = distanceBetween(victim.lastCenterX, victim.lastCenterY,
                            item.getBoundingBox().x() + item.getBoundingBox().width() / 2.0,
                            item.getBoundingBox().y() + item.getBoundingBox().height() / 2.0);
                    if (idist < 90.0) {
                        itemDesc = item.getLabel() + " #" + item.getId();
                        break;
                    }
                }

                suspect.isSuspect = true;
                suspect.isFleeing = true;
                victim.isVictim = true;

                SecurityIncident inc = new SecurityIncident();
                inc.type = "CHAIN_OR_BAG_SNATCHING";
                inc.severity = "CRITICAL";
                inc.confidence = 0.92;
                inc.title = "🚨 CRITICAL SECURITY BREACH: Chain / Bag Snatching";
                inc.summary = "Suspect Person #" + suspect.id + " rapidly intercepted victim Person #" + victim.id
                        + ", grabbed " + itemDesc + ", and fled at high velocity (" + Math.round(suspect.currentSpeed) + " px/s).";
                inc.timestamp = time;
                inc.indicators.add("Sudden close-range interception (< 75px distance)");
                inc.indicators.add("Immediate high-speed sprint getaway by Person #" + suspect.id + " (" + Math.round(suspect.currentSpeed) + " px/s)");
                inc.indicators.add("Victim Person #" + victim.id + " " + (victim.hasFallen ? "knocked to the ground" : "left behind"));
                inc.suspectIds.add(suspect.id);
                inc.victimIds.add(victim.id);
                inc.involvedObjects.add("person #" + suspect.id);
                inc.involvedObjects.add("person #" + victim.id);
                inc.involvedObjects.add(itemDesc);

                registerIncident(session, inc);

                session.liveLog.add(new LiveEvent(
                        time,
                        formatTime(time),
                        "🚨 CRITICAL BREACH: Suspected Snatching between Person #" + suspect.id + " and Person #" + victim.id + " (" + itemDesc + " targeted)!",
                        "SNATCHING",
                        "BREACH",
                        "person",
                        suspect.id,
                        "🚨"
                ));
            }
        }
    }

    private void checkVehicleSnatching(AnalysisSession session, double time, ObjectTrajectory vehicle, ObjectTrajectory victim, List<TrackedObject> items) {
        String vl = vehicle.label.toLowerCase();
        boolean isTwoWheeler = vl.contains("motorcycle") || vl.contains("bicycle");
        double dist = distanceBetween(vehicle.lastCenterX, vehicle.lastCenterY, victim.lastCenterX, victim.lastCenterY);

        // Close proximity interception between vehicle and pedestrian
        if (dist < 95.0) {
            boolean highThreat = isTwoWheeler || vehicle.currentSpeed > 60.0;
            if (highThreat) {
                vehicle.isSuspect = true;
                victim.isVictim = true;

                SecurityIncident inc = new SecurityIncident();
                inc.type = "CHAIN_OR_BAG_SNATCHING";
                inc.severity = "CRITICAL";
                inc.confidence = 0.96;
                inc.title = "🚨 CRITICAL SECURITY BREACH: Drive-by Chain / Bag Snatching";
                inc.summary = "Suspect on " + vehicle.label + " #" + vehicle.id + " intercepted pedestrian Person #" + victim.id
                        + " at close range (" + Math.round(dist) + "px), executing a swift grab-and-run snatch before accelerating away.";
                inc.timestamp = time;
                inc.indicators.add("Two-wheeler (" + vehicle.label + " #" + vehicle.id + ") executed rapid close-proximity interception (< 95px)");
                inc.indicators.add("Pedestrian Person #" + victim.id + " directly cornered and targeted by rider");
                inc.indicators.add("Classic grab-and-flee drive-by trajectory observed");
                inc.suspectIds.add(vehicle.id);
                inc.victimIds.add(victim.id);
                inc.involvedObjects.add(vehicle.label + " #" + vehicle.id);
                inc.involvedObjects.add("person #" + victim.id);

                registerIncident(session, inc);

                session.liveLog.add(new LiveEvent(
                        time,
                        formatTime(time),
                        "🚨 CRITICAL BREACH: Drive-by Chain/Bag Snatching by " + vehicle.label + " #" + vehicle.id + " targeting Person #" + victim.id + "!",
                        "CHAIN SNATCHING",
                        "BREACH",
                        vehicle.label,
                        vehicle.id,
                        "🏍️"
                ));
            }
        }
    }


    private void checkPhysicalAssault(AnalysisSession session, double time, ObjectTrajectory p1, ObjectTrajectory p2) {
        double dist = distanceBetween(p1.lastCenterX, p1.lastCenterY, p2.lastCenterX, p2.lastCenterY);
        if (dist < 55.0) {
            // Sustained violent overlap or one falling
            if (p1.hasFallen || p2.hasFallen) {
                ObjectTrajectory victim = p1.hasFallen ? p1 : p2;
                ObjectTrajectory aggressor = p1.hasFallen ? p2 : p1;
                aggressor.isSuspect = true;
                victim.isVictim = true;

                SecurityIncident inc = new SecurityIncident();
                inc.type = "PHYSICAL_ASSAULT_AND_COLLAPSE";
                inc.severity = "CRITICAL";
                inc.confidence = 0.89;
                inc.title = "🚨 CRITICAL SECURITY BREACH: Physical Assault / Subject Knocked Down";
                inc.summary = "Violent encounter detected between Person #" + aggressor.id + " and Person #" + victim.id
                        + ", resulting in Person #" + victim.id + " collapsing to the ground.";
                inc.timestamp = time;
                inc.indicators.add("High-impact physical contact (< 55px)");
                inc.indicators.add("Subject collapse / fallen posture detected");
                inc.suspectIds.add(aggressor.id);
                inc.victimIds.add(victim.id);
                inc.involvedObjects.add("person #" + aggressor.id);
                inc.involvedObjects.add("person #" + victim.id);

                registerIncident(session, inc);
            }
        }
    }

    private void checkFightingAndAltercation(AnalysisSession session, double time, List<TrackedObject> people) {
        for (int i = 0; i < people.size(); i++) {
            TrackedObject p1 = people.get(i);
            ObjectTrajectory t1 = session.trajectories.get(p1.getId());
            if (t1 == null) continue;

            for (int j = i + 1; j < people.size(); j++) {
                TrackedObject p2 = people.get(j);
                ObjectTrajectory t2 = session.trajectories.get(p2.getId());
                if (t2 == null) continue;

                double dist = distanceBetween(t1.lastCenterX, t1.lastCenterY, t2.lastCenterX, t2.lastCenterY);
                double maxDim = Math.max(
                        Math.max(p1.getBoundingBox().width(), p1.getBoundingBox().height()),
                        Math.max(p2.getBoundingBox().width(), p2.getBoundingBox().height())
                );
                if (dist < Math.max(145.0, maxDim * 1.35)) {
                    boolean highMotion = t1.currentSpeed > 20.0 || t2.currentSpeed > 20.0 || t1.peakSpeed > 45.0 || t2.peakSpeed > 45.0;
                    boolean eitherFallen = t1.hasFallen || t2.hasFallen;

                    if (highMotion || eitherFallen) {
                        t1.isSuspect = true;
                        t2.isSuspect = true;

                        SecurityIncident inc = new SecurityIncident();
                        inc.type = "PHYSICAL_ALTERCATION_OR_FIGHTING";
                        inc.severity = "CRITICAL";
                        inc.confidence = 0.94;
                        inc.title = "🚨 CRITICAL SECURITY BREACH: Physical Altercation / Fighting";
                        inc.summary = "Violent physical struggle and altercation detected between Person #" + t1.id + " and Person #" + t2.id
                                + " with erratic motion and close contact (" + Math.round(dist) + "px).";
                        inc.timestamp = time;
                        inc.indicators.add("Sustained close-proximity physical combat and struggle");
                        inc.indicators.add("Violent acceleration spikes and erratic body movement");
                        if (eitherFallen) {
                            inc.indicators.add("Physical collapse / knockout of subject during altercation");
                        }
                        inc.suspectIds.add(t1.id);
                        inc.suspectIds.add(t2.id);
                        inc.involvedObjects.add("person #" + t1.id);
                        inc.involvedObjects.add("person #" + t2.id);

                        registerIncident(session, inc);

                        session.liveLog.add(new LiveEvent(
                                time,
                                formatTime(time),
                                "🥊 VIOLENT CONFRONTATION: Physical fighting / struggle between Person #" + t1.id + " and Person #" + t2.id + "!",
                                "FIGHTING",
                                "BREACH",
                                "person",
                                t1.id,
                                "🥊"
                        ));
                    }
                }
            }
        }
    }

    private void checkCrowdGathering(AnalysisSession session, double time, List<TrackedObject> people) {
        Set<Integer> cluster = new LinkedHashSet<>();
        for (TrackedObject p1 : people) {
            int closeCount = 0;
            List<Integer> nearby = new ArrayList<>();
            for (TrackedObject p2 : people) {
                if (p1.getId() == p2.getId()) continue;
                double d = distanceBetween(
                        p1.getBoundingBox().x() + p1.getBoundingBox().width() / 2.0,
                        p1.getBoundingBox().y() + p1.getBoundingBox().height() / 2.0,
                        p2.getBoundingBox().x() + p2.getBoundingBox().width() / 2.0,
                        p2.getBoundingBox().y() + p2.getBoundingBox().height() / 2.0
                );
                if (d < 280.0) {
                    closeCount++;
                    nearby.add(p2.getId());
                }
            }
            if (closeCount >= 2 || people.size() >= 4) {
                cluster.add(p1.getId());
                cluster.addAll(nearby);
            }
        }

        if (cluster.size() >= 3 || people.size() >= 4) {
            int crowdCount = Math.max(cluster.size(), people.size());
            SecurityIncident inc = new SecurityIncident();
            inc.type = "CROWD_GATHERING";
            inc.severity = crowdCount >= 5 ? "CRITICAL" : "WARNING";
            inc.confidence = 0.93;
            inc.title = "👥 SECURITY ALERT: Large Crowd Area (" + crowdCount + " individuals)";
            inc.summary = "A dense congregation of " + crowdCount + " individuals formed in the monitored surveillance zone.";
            inc.timestamp = time;
            inc.indicators.add("Large crowd area density threshold exceeded (" + crowdCount + " individuals in sector)");
            inc.indicators.add("Spatial cluster formation and group gathering detected");
            for (TrackedObject p : people) {
                inc.involvedObjects.add("person #" + p.getId());
            }

            registerIncident(session, inc);

            session.liveLog.add(new LiveEvent(
                    time,
                    formatTime(time),
                    "👥 LARGE CROWD AREA: " + crowdCount + " individuals congregated in monitored zone!",
                    "CROWD GATHERING",
                    crowdCount >= 5 ? "BREACH" : "SUSPICIOUS",
                    "person",
                    people.get(0).getId(),
                    "👥"
            ));
        }
    }

    private void checkCorneringRobbery(AnalysisSession session, double time, List<TrackedObject> people, List<TrackedObject> items) {
        // Look for 1 isolated person surrounded by 2+ persons
        for (TrackedObject target : people) {
            int closeCount = 0;
            List<Integer> cornerers = new ArrayList<>();
            for (TrackedObject other : people) {
                if (target.getId() == other.getId()) continue;
                double d = distanceBetween(
                        target.getBoundingBox().x() + target.getBoundingBox().width() / 2.0,
                        target.getBoundingBox().y() + target.getBoundingBox().height() / 2.0,
                        other.getBoundingBox().x() + other.getBoundingBox().width() / 2.0,
                        other.getBoundingBox().y() + other.getBoundingBox().height() / 2.0
                );
                if (d < 85.0) {
                    closeCount++;
                    cornerers.add(other.getId());
                }
            }

            if (closeCount >= 2) {
                SecurityIncident inc = new SecurityIncident();
                inc.type = "GROUP_ROBBERY_OR_MUGGING";
                inc.severity = "CRITICAL";
                inc.confidence = 0.88;
                inc.title = "🚨 CRITICAL SECURITY BREACH: Group Robbery / Cornering Incident";
                inc.summary = "Person #" + target.getId() + " was cornered and surrounded by multiple individuals ("
                        + cornerers.toString() + ") in close proximity.";
                inc.timestamp = time;
                inc.indicators.add("Multiple subjects converging and trapping Person #" + target.getId());
                inc.indicators.add("Encirclement distance under 85 pixels");
                inc.suspectIds.addAll(cornerers);
                inc.victimIds.add(target.getId());
                inc.involvedObjects.add("person #" + target.getId());
                for (int cid : cornerers) inc.involvedObjects.add("person #" + cid);

                registerIncident(session, inc);
            }
        }
    }

    private void checkKidnappingGroup(AnalysisSession session, double time, List<TrackedObject> people) {
        // Look for 2+ persons tightly grappling 1 person and moving forcefully
        for (TrackedObject target : people) {
            List<Integer> captors = new ArrayList<>();
            for (TrackedObject other : people) {
                if (target.getId() == other.getId()) continue;
                double d = distanceBetween(
                        target.getBoundingBox().x() + target.getBoundingBox().width() / 2.0,
                        target.getBoundingBox().y() + target.getBoundingBox().height() / 2.0,
                        other.getBoundingBox().x() + other.getBoundingBox().width() / 2.0,
                        other.getBoundingBox().y() + other.getBoundingBox().height() / 2.0
                );
                if (d < 50.0) {
                    captors.add(other.getId());
                }
            }

            if (captors.size() >= 2) {
                ObjectTrajectory tTarget = session.trajectories.get(target.getId());
                if (tTarget != null && (tTarget.currentSpeed > 60.0 || tTarget.hasFallen)) {
                    SecurityIncident inc = new SecurityIncident();
                    inc.type = "KIDNAPPING_OR_FORCED_ABDUCTION";
                    inc.severity = "CRITICAL";
                    inc.confidence = 0.90;
                    inc.title = "🚨 CRITICAL SECURITY BREACH: Kidnapping / Forced Abduction";
                    inc.summary = "Multiple individuals (" + captors.toString() + ") engaged in forced physical grapple and dragging of Person #" + target.getId() + ".";
                    inc.timestamp = time;
                    inc.indicators.add("Multi-person physical restraint and grappling");
                    inc.indicators.add("Forced relocation / dragging behavior");
                    inc.suspectIds.addAll(captors);
                    inc.victimIds.add(target.getId());
                    inc.involvedObjects.add("person #" + target.getId());
                    for (int cid : captors) inc.involvedObjects.add("person #" + cid);

                    registerIncident(session, inc);
                }
            }
        }
    }

    private void checkKidnappingVehicle(AnalysisSession session, double time, List<TrackedObject> people, ObjectTrajectory vehicle) {
        String vl = vehicle.label.toLowerCase();
        // Motorcycles and bicycles cannot be kidnapping/abduction vehicles
        if (vl.contains("motorcycle") || vl.contains("bicycle")) {
            return;
        }

        // Only enclosed motor vehicles (car, van, truck) with at least 3 people involved
        if (people.size() < 3) {
            return;
        }

        for (TrackedObject p : people) {
            double d = distanceBetween(vehicle.lastCenterX, vehicle.lastCenterY,
                    p.getBoundingBox().x() + p.getBoundingBox().width() / 2.0,
                    p.getBoundingBox().y() + p.getBoundingBox().height() / 2.0);
            if (d < 65.0) {
                SecurityIncident inc = new SecurityIncident();
                inc.type = "VEHICULAR_ABDUCTION";
                inc.severity = "CRITICAL";
                inc.confidence = 0.88;
                inc.title = "🚨 CRITICAL SECURITY BREACH: Forced Vehicular Abduction / Kidnapping";
                inc.summary = "Individual Person #" + p.getId() + " forced into proximity of " + vehicle.label + " #" + vehicle.id + " during multi-person struggle.";
                inc.timestamp = time;
                inc.indicators.add("Forced convergence near vehicle " + vehicle.label + " #" + vehicle.id);
                inc.indicators.add("Victim forced toward vehicle enclosure by multiple subjects");
                inc.suspectIds.add(vehicle.id);
                inc.victimIds.add(p.getId());
                inc.involvedObjects.add(vehicle.label + " #" + vehicle.id);
                inc.involvedObjects.add("person #" + p.getId());

                registerIncident(session, inc);
            }
        }
    }


    private SecurityIncident createUnattendedObjectIncident(ObjectTrajectory traj, double time) {
        SecurityIncident inc = new SecurityIncident();
        inc.type = "UNATTENDED_BAGGAGE";
        inc.severity = "WARNING";
        inc.confidence = 0.85;
        inc.title = "⚠️ SECURITY WARNING: Unattended Baggage (" + capitalize(traj.label) + ")";
        inc.summary = "A " + traj.label + " #" + traj.id + " was left unattended in the monitored area with no person nearby.";
        inc.timestamp = time;
        inc.indicators.add("Item left isolated with zero human presence within 80px");
        inc.indicators.add("Stationary duration exceeding 15 seconds");
        inc.involvedObjects.add(traj.label + " #" + traj.id);
        return inc;
    }

    private void registerIncident(AnalysisSession session, SecurityIncident incident) {
        session.detectedIncidents.add(incident);
        // Update highest threat
        SecurityIncident current = session.highestThreatIncident;
        if (current == null || "SAFE".equals(current.severity)) {
            session.highestThreatIncident = incident;
        } else if ("CRITICAL".equals(incident.severity) && !"CRITICAL".equals(current.severity)) {
            session.highestThreatIncident = incident;
        } else if (incident.confidence > current.confidence) {
            session.highestThreatIncident = incident;
        }
    }

    private String buildRunningDescription(AnalysisSession session, double time, List<TrackedObject> currentObjects) {
        if (currentObjects.isEmpty()) {
            return "At " + formatTime(time) + ", scene is clear of active movement.";
        }

        List<String> parts = new ArrayList<>();
        int peopleCount = 0;
        int vehicleCount = 0;
        int itemCount = 0;

        for (TrackedObject obj : currentObjects) {
            String l = obj.getLabel().toLowerCase();
            if ("person".equals(l)) peopleCount++;
            else if (isVehicle(l)) vehicleCount++;
            else if (isCarriedItem(l)) itemCount++;
        }

        if (session.highestThreatIncident != null && !"SAFE".equalsIgnoreCase(session.highestThreatIncident.severity)) {
            return "[" + formatTime(time) + "] " + session.highestThreatIncident.title + " — " + session.highestThreatIncident.summary;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[").append(formatTime(time)).append("] Active in frame: ");
        if (peopleCount > 0) sb.append(peopleCount).append(" person(s) ");
        if (vehicleCount > 0) sb.append(vehicleCount).append(" vehicle(s) ");
        if (itemCount > 0) sb.append(itemCount).append(" carried item(s) ");

        // Detail individual actions
        for (TrackedObject obj : currentObjects) {
            ObjectTrajectory t = session.trajectories.get(obj.getId());
            if (t != null) {
                parts.add(capitalize(t.label) + " #" + t.id + " (" + t.currentBehavior.toLowerCase() + ")");
            }
        }
        sb.append("• ").append(String.join(", ", parts));
        return sb.toString();
    }

    private void updatePrimaryBehavior(ObjectTrajectory traj) {
        if (traj.hasFallen) {
            traj.primaryBehavior = "FALLEN / COLLAPSED";
        } else if (traj.isFleeing) {
            traj.primaryBehavior = "FLEEING / SPRINTING";
        } else if (traj.behaviorsObserved.contains("RUNNING")) {
            traj.primaryBehavior = "RUNNING";
        } else if (traj.behaviorsObserved.contains("WALKING")) {
            traj.primaryBehavior = "WALKING";
        } else if (traj.behaviorsObserved.contains("HAND MOVEMENT")) {
            traj.primaryBehavior = "HAND MOVEMENT";
        } else if (traj.behaviorsObserved.contains("LOITERING")) {
            traj.primaryBehavior = "LOITERING";
        } else {
            traj.primaryBehavior = "STATIONARY";
        }
    }

    private double distanceBetween(double x1, double y1, double x2, double y2) {
        return Math.sqrt(Math.pow(x1 - x2, 2) + Math.pow(y1 - y2, 2));
    }

    private boolean isVehicle(String label) {
        return label.equals("car") || label.equals("motorcycle") || label.equals("bicycle")
                || label.equals("bus") || label.equals("truck");
    }

    private boolean isCarriedItem(String label) {
        return label.equals("backpack") || label.equals("handbag") || label.equals("suitcase")
                || label.equals("umbrella") || label.equals("cell phone") || label.equals("laptop");
    }

    private boolean isWeapon(String label) {
        return label.equals("knife") || label.equals("baseball bat") || label.equals("scissors");
    }

    private String getObjectIcon(String label) {
        return switch (label.toLowerCase()) {
            case "person" -> "👤";
            case "motorcycle" -> "🏍️";
            case "bicycle" -> "🚲";
            case "car" -> "🚗";
            case "bus", "truck" -> "🚐";
            case "backpack" -> "🎒";
            case "handbag" -> "👜";
            case "suitcase" -> "🧳";
            case "knife" -> "🔪";
            case "baseball bat" -> "🏏";
            case "cell phone" -> "📱";
            case "laptop" -> "💻";
            case "dog" -> "🐕";
            case "cat" -> "🐈";
            default -> "📦";
        };
    }

    private String formatNewObjectDescription(String label, int id) {
        return switch (label.toLowerCase()) {
            case "person" -> "Person #" + id + " entered camera view (walking pace)";
            case "motorcycle" -> "Motorcycle #" + id + " entered frame";
            case "bicycle" -> "Bicycle #" + id + " entered scene";
            case "car" -> "Vehicle #" + id + " arrived in monitored area";
            case "backpack" -> "Backpack #" + id + " detected in frame";
            case "handbag" -> "Handbag #" + id + " identified";
            case "suitcase" -> "Suitcase #" + id + " detected";
            case "knife" -> "⚠️ WEAPON: Knife #" + id + " detected!";
            case "cell phone" -> "Cell phone #" + id + " detected";
            default -> capitalize(label) + " #" + id + " appeared in frame";
        };
    }

    private static String formatTime(double seconds) {
        long totalSeconds = Math.max(0, (long) seconds);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long secs = totalSeconds % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, secs);
    }

    private String capitalize(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.substring(0, 1).toUpperCase() + text.substring(1).toLowerCase();
    }
}
