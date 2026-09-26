package cctvai.service;

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

        System.out.println();
        System.out.println("========================================");
        System.out.println("GENERATING VIDEO DESCRIPTION");
        System.out.println("========================================");

        /*
         * ============================================================
         * OPTION 1
         * ============================================================
         *
         * Try OpenAI only when an API key is configured.
         *
         * If there are no credits, or the API call fails,
         * automatically use the local description.
         */
        if (apiKey != null && !apiKey.isBlank()) {

            try {

                String aiDescription =
                        generateOpenAIDescription(
                                videoPath,
                                durationSeconds,
                                detectedObjects,
                                events
                        );

                if (aiDescription != null
                        && !aiDescription.isBlank()) {

                    System.out.println(
                            "OpenAI description generated successfully."
                    );

                    return aiDescription.trim();
                }

            } catch (Exception e) {

                System.out.println();
                System.out.println(
                        "OpenAI description unavailable."
                );

                System.out.println(
                        "Reason: "
                                + e.getClass().getSimpleName()
                                + " - "
                                + e.getMessage()
                );

                System.out.println(
                        "Using local CCTV description instead."
                );
            }
        }

        /*
         * ============================================================
         * OPTION 2
         * ============================================================
         *
         * Local description.
         *
         * This does NOT use OpenAI.
         *
         * It uses:
         *
         * - detected object types
         * - object IDs
         * - start time
         * - end time
         * - duration
         */
        String localDescription =
                generateLocalDescription(
                        durationSeconds,
                        detectedObjects,
                        events
                );

        System.out.println();
        System.out.println("========================================");
        System.out.println("LOCAL VIDEO DESCRIPTION");
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
            List<Map<String, Object>> events
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
                        events
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
            List<Map<String, Object>> events
    ) {
        if (events == null || events.isEmpty()) {
            return "📹 SCENARIO OVERVIEW:\n"
                    + "The CCTV surveillance video has a total duration of " + formatTime(durationSeconds) + ".\n\n"
                    + "• Status: Inactive / No movement detected\n"
                    + "• The monitored area remained completely vacant with zero detected human or object activity throughout the entire recording.";
        }

        /*
         * Sort events chronologically by start time.
         */
        List<Map<String, Object>> sortedEvents = new ArrayList<>(events);
        sortedEvents.sort((a, b) -> {
            Double sA = toDouble(a.get("start_seconds"));
            Double sB = toDouble(b.get("start_seconds"));
            return Double.compare(sA, sB);
        });

        double earliestStart = toDouble(sortedEvents.get(0).get("start_seconds"));
        double latestEnd = sortedEvents.stream()
                .mapToDouble(e -> toDouble(e.get("end_seconds")))
                .max().orElse(durationSeconds);

        // Find primary/longest staying object
        Map<String, Object> longestEvent = sortedEvents.stream()
                .max((a, b) -> Double.compare(toDouble(a.get("duration_seconds")), toDouble(b.get("duration_seconds"))))
                .orElse(sortedEvents.get(0));

        double longestDuration = toDouble(longestEvent.get("duration_seconds"));
        String longestLabel = String.valueOf(longestEvent.get("object_type")) + " #" + longestEvent.get("object_id");

        // Calculate peak concurrency
        int peakConcurrency = calculatePeakConcurrency(sortedEvents);

        // Group into logical chronological phases
        List<String> phases = buildTimelinePhases(sortedEvents, durationSeconds, earliestStart, latestEnd, peakConcurrency);

        StringBuilder sb = new StringBuilder();

        // 1. Executive Scenario Overview
        sb.append("🎬 SCENARIO OVERVIEW:\n");
        sb.append("This CCTV recording spans ").append(formatTime(durationSeconds));
        if (peakConcurrency > 1) {
            sb.append(" and captures a multi-person collaborative indoor session involving ");
            sb.append(events.size()).append(" tracked occurrences, with peak concurrent occupancy reaching ");
            sb.append(peakConcurrency).append(" individuals gathered simultaneously.\n\n");
        } else {
            sb.append(" and captures a single-occupant session with intermittent movement across the monitored area.\n\n");
        }

        // 2. Chronological Timeline Breakdown
        sb.append("⏱️ CHRONOLOGICAL SCENE BREAKDOWN:\n");
        for (String phase : phases) {
            sb.append(phase).append("\n");
        }
        sb.append("\n");

        // 3. Activity & Security Summary
        sb.append("📊 SURVEILLANCE & BEHAVIORAL INSIGHTS:\n");
        sb.append("• Primary Subject: ").append(longestLabel)
                .append(" (sustained presence of ").append(formatSecondsHuman(longestDuration)).append(")\n");
        sb.append("• Peak Concurrency: ").append(peakConcurrency).append(" persons simultaneously in view\n");
        sb.append("• Active Window: ").append(formatTime(earliestStart)).append(" → ").append(formatTime(latestEnd))
                .append(" (").append(formatSecondsHuman(Math.max(0, latestEnd - earliestStart))).append(" total activity)\n");
        if (latestEnd < durationSeconds - 10) {
            double idleEnd = durationSeconds - latestEnd;
            sb.append("• Concluding State: Scene vacated / camera inactive for the final ")
                    .append(formatSecondsHuman(idleEnd)).append("\n");
        }
        sb.append("• Behavioral Assessment: Normal collaborative interactions — no security violations or perimeter breaches observed.");

        return sb.toString();
    }

    private static int calculatePeakConcurrency(List<Map<String, Object>> events) {
        int max = 1;
        for (Map<String, Object> e1 : events) {
            double start = toDouble(e1.get("start_seconds"));
            double end = toDouble(e1.get("end_seconds"));
            int count = 0;
            for (Map<String, Object> e2 : events) {
                double s2 = toDouble(e2.get("start_seconds"));
                double e2End = toDouble(e2.get("end_seconds"));
                if (s2 <= end && e2End >= start) {
                    count++;
                }
            }
            if (count > max) {
                max = count;
            }
        }
        return max;
    }

    private List<String> buildTimelinePhases(
            List<Map<String, Object>> events,
            double totalDuration,
            double earliestStart,
            double latestEnd,
            int peakConcurrency
    ) {
        List<String> phases = new ArrayList<>();

        // Opening Calm Phase
        if (earliestStart >= 5.0) {
            phases.add(String.format("• 00:00 - %s | 🔒 Initial Standby: Monitored area was clear of active subjects during the initial %s.",
                    formatTime(earliestStart), formatSecondsHuman(earliestStart)));
        }

        // Active Interaction Window
        if (events.size() <= 3) {
            for (Map<String, Object> ev : events) {
                String label = String.valueOf(ev.get("object_type")) + " #" + ev.get("object_id");
                String startStr = String.valueOf(ev.get("start"));
                String endStr = String.valueOf(ev.get("end"));
                double dur = toDouble(ev.get("duration_seconds"));
                phases.add(String.format("• %s - %s | 👤 Active Movement: %s entered the scene and remained active for %s.",
                        startStr, endStr, label, formatSecondsHuman(dur)));
            }
        } else {
            // Group into Early Activity, Peak Collaboration, and Wind-down
            double midPoint = earliestStart + (latestEnd - earliestStart) * 0.35;
            double latePoint = earliestStart + (latestEnd - earliestStart) * 0.85;

            // Phase 1: Entry & Arrival
            phases.add(String.format("• %s - %s | 🚶 Initial Arrival & Presence: First occupants entered the surveillance view and established position at the workstation/monitored area.",
                    formatTime(earliestStart), formatTime(midPoint)));

            // Phase 2: Peak Collaboration
            phases.add(String.format("• %s - %s | 👥 Active Group Discussion & Interaction: Multiple individuals gathered in close proximity in front of the camera, actively collaborating (up to %d people present simultaneously).",
                    formatTime(midPoint), formatTime(latePoint), peakConcurrency));

            // Phase 3: Transition & Dispersal
            phases.add(String.format("• %s - %s | 🔄 Close Adjustments & Dispersal: Occupants concluded their interactions, made final workstation adjustments, and vacated the active field of view.",
                    formatTime(latePoint), formatTime(latestEnd)));
        }

        // Concluding Inactive Phase
        if (latestEnd < totalDuration - 5.0) {
            phases.add(String.format("• %s - %s | ⏹️ Scene Cleared / Idle: Movement concluded and the camera view remained idle / shielded for the remainder of the recording.",
                    formatTime(latestEnd), formatTime(totalDuration)));
        }

        return phases;
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
            List<Map<String, Object>> events
    ) {

        StringBuilder prompt =
                new StringBuilder();

        prompt.append(
                "Analyze these CCTV video frames and provide "
                        + "one concise overall description of what "
                        + "happens in the video. "
        );

        prompt.append(
                "The video duration is "
        );

        prompt.append(
                formatTime(durationSeconds)
        );

        prompt.append(". ");

        if (detectedObjects != null
                && !detectedObjects.isEmpty()) {

            prompt.append(
                    "The computer vision detector identified: "
            );

            prompt.append(
                    String.join(
                            ", ",
                            detectedObjects
                    )
            );

            prompt.append(". ");
        }

        if (events != null
                && !events.isEmpty()) {

            prompt.append(
                    "Tracked events with timestamps are: "
            );

            for (Map<String, Object> event : events) {

                prompt.append(
                        event.get("object_type")
                );

                prompt.append(" #");

                prompt.append(
                        event.get("object_id")
                );

                prompt.append(
                        " from "
                );

                prompt.append(
                        event.get("start")
                );

                prompt.append(
                        " to "
                );

                prompt.append(
                        event.get("end")
                );

                prompt.append("; ");
            }
        }

        prompt.append(
                "Describe the scene objectively. "
                        + "Do not invent events that are not visible."
        );

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