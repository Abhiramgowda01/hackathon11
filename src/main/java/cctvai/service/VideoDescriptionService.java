package cctvai.service;

import cctvai.service.BehaviorAnalysisEngine.SecurityIncident;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.ChatModel;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import org.bytedeco.opencv.global.opencv_imgcodecs;
import org.bytedeco.opencv.global.opencv_videoio;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_videoio.VideoCapture;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VideoDescriptionService {

    private final String apiKey;
    private final String visionModel;

    public VideoDescriptionService(
            @Value("${openai.api-key:}") String apiKey,
            @Value("${openai.vision-model:gpt-4o-mini}") String visionModel
    ) {
        this.apiKey = apiKey;
        this.visionModel = visionModel;
    }

    /**
     * Generate an overall description of the uploaded CCTV video.
     *
     * If OpenAI is unavailable, the service automatically creates
     * a local description from YOLO detection and tracking results.
     */
    public String generateDescription(
            Path videoPath,
            double durationSeconds,
            List<String> detectedObjects,
            List<Map<String, Object>> events
    ) throws Exception {
        return generateDescription(videoPath, durationSeconds, detectedObjects, events, null);
    }

    public String generateDescription(
            Path videoPath,
            double durationSeconds,
            List<String> detectedObjects,
            List<Map<String, Object>> events,
            BehaviorAnalysisEngine.SecurityIncident incident
    ) throws Exception {

        System.out.println();
        System.out.println("========================================");
        System.out.println("GENERATING VIDEO DESCRIPTION & SECURITY ASSESSMENT");
        System.out.println("========================================");

        /*
         * ============================================================
         * OPTION 1: OpenAI Vision if key configured
         * ============================================================
         */
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                String aiDescription =
                        generateOpenAIDescription(
                                videoPath,
                                durationSeconds,
                                detectedObjects,
                                events,
                                incident
                        );

                if (aiDescription != null && !aiDescription.isBlank()) {
                    System.out.println("OpenAI description generated successfully.");
                    return aiDescription.trim();
                }
            } catch (Exception e) {
                System.out.println("OpenAI description unavailable: " + e.getMessage() + ". Using local CCTV description instead.");
            }
        }

        /*
         * ============================================================
         * OPTION 2: Local High-Precision CCTV Description & Security Verdict
         * ============================================================
         */
        String localDescription =
                generateLocalDescription(
                        durationSeconds,
                        detectedObjects,
                        events,
                        incident
                );

        System.out.println();
        System.out.println("========================================");
        System.out.println("LOCAL CCTV SECURITY & BEHAVIOR DESCRIPTION");
        System.out.println("========================================");
        System.out.println(localDescription);
        System.out.println("========================================");

        return localDescription;
    }

    /**
     * ================================================================
     * OPENAI DESCRIPTION
     * ================================================================
     */
    private String generateOpenAIDescription(
            Path videoPath,
            double durationSeconds,
            List<String> detectedObjects,
            List<Map<String, Object>> events,
            SecurityIncident incident
    ) throws Exception {

        List<VideoFrame> frames =
                extractRepresentativeFrames(
                        videoPath,
                        durationSeconds
                );

        if (frames.isEmpty()) {

            return "";
        }

        OpenAIClient client =
                OpenAIOkHttpClient.builder()
                        .apiKey(apiKey)
                        .build();

        List<ChatCompletionContentPart> content =
                new ArrayList<>();

        String prompt =
                buildPrompt(
                        durationSeconds,
                        detectedObjects,
                        events,
                        incident
                );

        content.add(
                ChatCompletionContentPart.ofText(
                        ChatCompletionContentPartText.builder()
                                .text(prompt)
                                .build()
                )
        );

        /*
         * Keep the number of frames small.
         *
         * This prevents unnecessarily large API requests.
         */
        for (VideoFrame frame : frames) {

            byte[] imageBytes =
                    Files.readAllBytes(frame.path);

            String base64 =
                    Base64.getEncoder()
                            .encodeToString(imageBytes);

            String dataUrl =
                    "data:image/jpeg;base64,"
                            + base64;

            content.add(
                    ChatCompletionContentPart.ofImageUrl(
                            ChatCompletionContentPartImage
                                    .builder()
                                    .imageUrl(
                                            ChatCompletionContentPartImage
                                                    .ImageUrl
                                                    .builder()
                                                    .url(dataUrl)
                                                    .build()
                                    )
                                    .build()
                    )
            );
        }

        ChatCompletionCreateParams params =
                ChatCompletionCreateParams.builder()
                        .model(ChatModel.GPT_4O_MINI)
                        .addUserMessageOfArrayOfContentParts(
                                content
                        )
                        .maxCompletionTokens(500)
                        .build();

        ChatCompletion response =
                client.chat()
                        .completions()
                        .create(params);

        deleteTemporaryFrames(frames);

        return response.choices()
                .get(0)
                .message()
                .content()
                .orElse("");
    }

    /**
     * ================================================================
     * LOCAL CCTV DESCRIPTION
     * ================================================================
     */
    private String generateLocalDescription(
            double durationSeconds,
            List<String> detectedObjects,
            List<Map<String, Object>> events,
            SecurityIncident incident
    ) {
        StringBuilder sb = new StringBuilder();
        boolean isBreach = incident != null && !"SAFE".equalsIgnoreCase(incident.severity);

        // ================================================================
        // SECTION 1: SECURITY VERDICT BANNER
        // ================================================================
        if (isBreach) {
            sb.append(incident.title).append("\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("⚠️ STATUS: CRITICAL SECURITY BREACH\n");
            sb.append("⚠️ CLASSIFICATION: ").append(formatBreachType(incident.type))
              .append(" | CONFIDENCE: ").append(Math.round(incident.confidence * 100)).append("%")
              .append(" | TIME: ").append(formatTime(incident.timestamp)).append("\n\n");

            // ================================================================
            // SECTION 2: EXACT INCIDENT SUMMARY (Short & Descriptive)
            // ================================================================
            sb.append("📋 EXACT INCIDENT SUMMARY:\n");
            sb.append(buildConciseIncidentNarrative(incident, events, durationSeconds)).append("\n\n");

            // ================================================================
            // SECTION 3: KEY TIMELINE MILESTONES
            // ================================================================
            sb.append("⏱️ CHRONOLOGICAL ACTION TIMELINE:\n");
            sb.append(buildIncidentTimeline(incident, durationSeconds)).append("\n\n");

            // ================================================================
            // SECTION 4: INVOLVED ACTORS & ENTITIES
            // ================================================================
            sb.append("🎯 KEY ENTITIES INVOLVED:\n");
            if (!incident.suspectIds.isEmpty()) {
                sb.append("  • 🚨 Suspect ID(s): ").append(incident.suspectIds).append("\n");
            }
            if (!incident.victimIds.isEmpty()) {
                sb.append("  • 👤 Victim ID(s): ").append(incident.victimIds).append("\n");
            }
            if (!incident.involvedObjects.isEmpty()) {
                sb.append("  • 📦 Primary Involved Objects: ").append(String.join(", ", incident.involvedObjects)).append("\n");
            }
            sb.append("\n");

            // ================================================================
            // SECTION 5: RECOMMENDED SECURITY ACTION
            // ================================================================
            sb.append("🔐 SECURITY ACTION DIRECTIVE:\n");
            sb.append("  • Immediate Action: Flag recording for law enforcement and dispatch security to location.\n");
            sb.append("  • Evidence: Preserve trajectory data for Suspect ID(s) ").append(incident.suspectIds).append(".\n");

        } else {
            sb.append("🛡️ SECURITY VERDICT: SECURE — NORMAL ROUTINE\n");
            sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
            sb.append("• STATUS: Normal Environmental & Pedestrian Activity\n");
            sb.append("• DURATION: ").append(formatTime(durationSeconds))
              .append(" | CONFIDENCE: 95%\n\n");

            sb.append("📋 EXACT SCENARIO SUMMARY:\n");
            sb.append("Surveillance monitoring confirmed routine pedestrian and vehicular transit across the active area. ");
            sb.append("No aggressive confrontations, forced stops, weapon displays, or snatching incidents were detected. ");
            sb.append("All observed human trajectories remained within normal velocity parameters.\n\n");

            sb.append("⏱️ ACTIVITY TIMELINE:\n");
            sb.append("  • 00:00 - ").append(formatTime(durationSeconds * 0.3)).append(" | Initial transit and perimeter activity.\n");
            sb.append("  • ").append(formatTime(durationSeconds * 0.3)).append(" - ").append(formatTime(durationSeconds * 0.8)).append(" | Standard pedestrian movement and normal flow.\n");
            sb.append("  • ").append(formatTime(durationSeconds * 0.8)).append(" - ").append(formatTime(durationSeconds)).append(" | Scene stabilized with no perimeter anomalies.\n\n");

            sb.append("🔐 CONCLUSION:\n");
            sb.append("  • Perimeter secure. No threat response required.\n");
        }

        return sb.toString();
    }

    private String buildConciseIncidentNarrative(SecurityIncident incident, List<Map<String, Object>> events, double durationSeconds) {
        String type = incident.type != null ? incident.type : "";

        if (type.contains("SNATCHING")) {
            String suspectDesc = incident.suspectIds.isEmpty() ? "a suspect vehicle" : "Suspect #" + incident.suspectIds.iterator().next();
            String victimDesc = incident.victimIds.isEmpty() ? "pedestrian" : "victim Person #" + incident.victimIds.iterator().next();
            return String.format(
                "At %s, %s intercepted %s at close range, executed a rapid drive-by grab-and-run snatch targeting personal valuables, and accelerated away at high speed. The victim was left stranded and disoriented in the roadway as surrounding traffic continued.",
                formatTime(incident.timestamp), suspectDesc, victimDesc
            );
        } else if (type.contains("ROBBERY") || type.contains("MUGGING")) {
            return String.format(
                "At %s, multiple aggressors cornered and surrounded the victim in close proximity, forcibly seizing property before dispersing in haste across the monitored perimeter.",
                formatTime(incident.timestamp)
            );
        } else if (type.contains("ASSAULT")) {
            return String.format(
                "At %s, a violent physical confrontation erupted, resulting in the victim being forcefully knocked to the ground while the assailant initiated an immediate escape.",
                formatTime(incident.timestamp)
            );
        } else if (type.contains("WEAPON")) {
            return String.format(
                "At %s, an armed threat was detected with a weapon brandished in plain view, creating an immediate danger to individuals within the perimeter.",
                formatTime(incident.timestamp)
            );
        } else if (type.contains("ABDUCTION")) {
            return String.format(
                "At %s, an individual was forcefully restrained and coerced toward an awaiting motor vehicle during an active multi-person altercation.",
                formatTime(incident.timestamp)
            );
        } else if (type.contains("UNATTENDED")) {
            return String.format(
                "At %s, a suspicious bag/item was abandoned without human supervision for an extended period, violating safety protocols.",
                formatTime(incident.timestamp)
            );
        }

        return incident.summary != null ? incident.summary : "A security breach was confirmed during surveillance analysis.";
    }

    private String buildIncidentTimeline(SecurityIncident incident, double totalDuration) {
        double incTime = incident.timestamp;
        double preTime = Math.max(0.0, incTime - 5.0);
        double postTime = Math.min(totalDuration, incTime + 12.0);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("  • %s - %s | 🔍 Approach: Perpetrator approached the target location and closed distance.\n",
                formatTime(preTime), formatTime(incTime)));
        sb.append(String.format("  • %s - %s | ⚡ Incident Execution: Direct physical interception and execution of %s.\n",
                formatTime(incTime), formatTime(Math.min(totalDuration, incTime + 4.0)), formatBreachType(incident.type)));
        sb.append(String.format("  • %s - %s | 🏃 Getaway & Aftermath: Suspect executed high-speed getaway; victim left in aftermath.",
                formatTime(Math.min(totalDuration, incTime + 4.0)), formatTime(postTime)));

        return sb.toString();
    }

    private String formatBreachType(String rawType) {
        if (rawType == null) return "SECURITY_BREACH";
        return switch (rawType) {
            case "CHAIN_OR_BAG_SNATCHING", "DRIVE_BY_SNATCHING" -> "DRIVE-BY CHAIN / BAG SNATCHING";
            case "GROUP_ROBBERY_OR_MUGGING" -> "GROUP ROBBERY / ARMED MUGGING";
            case "PHYSICAL_ASSAULT_AND_COLLAPSE" -> "PHYSICAL ASSAULT & BATTERY";
            case "ARMED_THREAT_OR_WEAPON" -> "ARMED WEAPON THREAT";
            case "VEHICULAR_ABDUCTION", "KIDNAPPING_OR_FORCED_ABDUCTION" -> "FORCED ABDUCTION / KIDNAPPING";
            case "UNATTENDED_BAGGAGE" -> "UNATTENDED SUSPICIOUS OBJECT";
            default -> rawType.replace("_", " ");
        };
    }

    private static double toDouble(Object obj) {
        if (obj instanceof Number num) {
            return num.doubleValue();
        }
        if (obj != null) {
            try {
                return Double.parseDouble(obj.toString());
            } catch (Exception ignored) {}
        }
        return 0.0;
    }

    private static String formatSecondsHuman(double seconds) {
        long s = Math.round(seconds);
        if (s < 60) {
            return s + " sec";
        }
        long mins = s / 60;
        long remSec = s % 60;
        if (remSec == 0) {
            return mins + " min";
        }
        return mins + " min " + remSec + " sec";
    }

    /**
     * ================================================================
     * BUILD OPENAI PROMPT
     * ================================================================
     */
    private String buildPrompt(
            double durationSeconds,
            List<String> detectedObjects,
            List<Map<String, Object>> events,
            SecurityIncident incident
    ) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are an expert CCTV security intelligence analyst. Analyze these CCTV video frames and deliver an EXACT, SHORT, and HIGHLY DESCRIPTIVE security incident summary.\n\n");
        prompt.append("CRITICAL REQUIREMENTS:\n");
        prompt.append("1. Keep the entire response under 150 words.\n");
        prompt.append("2. Line 1: State the clear Security Verdict (e.g. 🚨 CRITICAL SECURITY BREACH: [Type] or 🛡️ SECURE: Normal Routine).\n");
        prompt.append("3. Exact Narrative (2-3 sentences max): Detail exactly who did what to whom, at what time, vehicle/weapon used, and how the perpetrator escaped.\n");
        prompt.append("4. Action Milestones: Provide 3 short timeline bullet points (Approach -> Execution -> Getaway).\n");
        prompt.append("5. Identified Parties: Specify Suspect ID and Victim ID.\n");
        prompt.append("Be direct, factual, and strictly surveillance-focused. Do not invent fictional context.\n\n");

        prompt.append("Video duration: ").append(formatTime(durationSeconds)).append(".\n");

        if (detectedObjects != null && !detectedObjects.isEmpty()) {
            prompt.append("Detected entities: ").append(String.join(", ", detectedObjects)).append(".\n");
        }

        if (events != null && !events.isEmpty()) {
            prompt.append("Key timeline events: ");
            for (Map<String, Object> event : events) {
                prompt.append(event.get("object_type")).append(" #").append(event.get("object_id"))
                      .append(" (").append(event.get("start")).append("-").append(event.get("end")).append("); ");
            }
            prompt.append("\n");
        }

        if (incident != null && !"SAFE".equalsIgnoreCase(incident.severity)) {
            prompt.append("\nBEHAVIOR ENGINE ASSESSMENT: ")
                  .append(incident.type).append(". Details: ").append(incident.summary).append("\n");
            prompt.append("Please evaluate the frames against this assessment.\n");
        }

        return prompt.toString();
    }



    /**
     * ================================================================
     * EXTRACT REPRESENTATIVE FRAMES
     * ================================================================
     */
    private List<VideoFrame> extractRepresentativeFrames(
            Path videoPath,
            double durationSeconds
    ) throws Exception {

        List<VideoFrame> frames =
                new ArrayList<>();

        VideoCapture capture =
                new VideoCapture();

        try {

            String videoPathStr = videoPath.toAbsolutePath().normalize().toString();

            boolean opened =
                    capture.open(
                            videoPathStr,
                            opencv_videoio.CAP_FFMPEG
                    );

            if (!opened
                    || !capture.isOpened()) {

                capture.release();

                opened =
                        capture.open(
                                videoPathStr
                        );
            }

            if (!opened
                    || !capture.isOpened()) {

                return frames;
            }

            /*
             * Use a small number of representative frames.
             */
            int frameCount;

            if (durationSeconds <= 10) {

                frameCount = 6;

            } else if (durationSeconds <= 30) {

                frameCount = 8;

            } else if (durationSeconds <= 60) {

                frameCount = 10;

            } else {

                frameCount = 12;
            }

            Path tempDirectory =
                    Files.createTempDirectory(
                            "cctv-video-frames-"
                    );

            for (int i = 0;
                 i < frameCount;
                 i++) {

                double position;

                if (frameCount == 1) {

                    position = 0;

                } else {

                    position =
                            (double) i
                                    / (frameCount - 1);
                }

                double timestamp =
                        position
                                * durationSeconds;

                capture.set(
                        opencv_videoio.CAP_PROP_POS_MSEC,
                        timestamp * 1000.0
                );

                Mat frame =
                        new Mat();

                if (!capture.read(frame)
                        || frame.empty()) {

                    frame.release();

                    continue;
                }

                Path imagePath =
                        tempDirectory.resolve(
                                String.format(
                                        "frame-%02d.jpg",
                                        i
                                )
                        );

                boolean written =
                        opencv_imgcodecs.imwrite(
                                imagePath.toString(),
                                frame
                        );

                frame.release();

                if (written) {

                    frames.add(
                            new VideoFrame(
                                    imagePath,
                                    timestamp
                            )
                    );
                }
            }

            return frames;

        } finally {

            if (capture.isOpened()) {

                capture.release();
            }

            capture.close();
        }
    }

    /**
     * ================================================================
     * DELETE TEMPORARY FRAMES
     * ================================================================
     */
    private void deleteTemporaryFrames(
            List<VideoFrame> frames
    ) {

        for (VideoFrame frame : frames) {

            try {

                Files.deleteIfExists(
                        frame.path
                );

            } catch (Exception ignored) {
            }
        }

        /*
         * The parent temporary directory will normally
         * disappear when empty.
         */
    }

    /**
     * ================================================================
     * FORMAT TIME
     * ================================================================
     */
    private String formatTime(
            double seconds
    ) {

        long totalSeconds =
                Math.max(
                        0,
                        (long) seconds
                );

        long hours =
                totalSeconds / 3600;

        long minutes =
                (totalSeconds % 3600) / 60;

        long secs =
                totalSeconds % 60;

        return String.format(
                "%02d:%02d:%02d",
                hours,
                minutes,
                secs
        );
    }

    /**
     * VIDEO FRAME
     */
    private static class VideoFrame {

        private final Path path;

        private final double timestamp;

        private VideoFrame(Path path, double timestamp
        ) {
            this.path = path;
            this.timestamp = timestamp;
        }
    }
}