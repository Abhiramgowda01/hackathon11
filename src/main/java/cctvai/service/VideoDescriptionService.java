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

        StringBuilder description =
                new StringBuilder();

        description.append(
                "The CCTV video has a duration of "
        );

        description.append(
                formatTime(durationSeconds)
        );

        description.append(". ");

        /*
         * ------------------------------------------------------------
         * DETECTED OBJECT TYPES
         * ------------------------------------------------------------
         */
        if (detectedObjects == null
                || detectedObjects.isEmpty()) {

            description.append(
                    "No supported objects were detected during "
                            + "the video analysis."
            );

        } else {

            description.append(
                    "The following object types were detected: "
            );

            for (int i = 0;
                 i < detectedObjects.size();
                 i++) {

                String object =
                        detectedObjects.get(i);

                description.append(object);

                if (i < detectedObjects.size() - 2) {

                    description.append(", ");

                } else if (i
                        == detectedObjects.size() - 2) {

                    description.append(" and ");

                }
            }

            description.append(". ");
        }

        /*
         * ------------------------------------------------------------
         * EVENT INFORMATION
         * ------------------------------------------------------------
         */
        if (events == null
                || events.isEmpty()) {

            description.append(
                    "No object events with tracking timestamps "
                            + "were recorded."
            );

            return description.toString();
        }

        description.append(
                "A total of "
        );

        description.append(events.size());

        description.append(
                " tracked object event"
        );

        if (events.size() != 1) {

            description.append("s");
        }

        description.append(
                " were recorded."
        );

        /*
         * ------------------------------------------------------------
         * EVENT DETAILS
         * ------------------------------------------------------------
         */
        description.append(
                " Event details: "
        );

        for (int i = 0;
             i < events.size();
             i++) {

            Map<String, Object> event =
                    events.get(i);

            Object objectType =
                    event.get("object_type");

            Object objectId =
                    event.get("object_id");

            Object start =
                    event.get("start");

            Object end =
                    event.get("end");

            Object duration =
                    event.get("duration_seconds");

            description.append(
                    objectType != null
                            ? objectType
                            : "unknown object"
            );

            if (objectId != null) {

                description.append(
                        " #"
                );

                description.append(
                        objectId
                );
            }

            if (start != null) {

                description.append(
                        " was detected at "
                );

                description.append(
                        start
                );
            }

            if (end != null) {

                description.append(
                        " and remained tracked until "
                );

                description.append(
                        end
                );
            }

            if (duration != null) {

                description.append(
                        " (duration: "
                );

                description.append(
                        duration
                );

                description.append(
                        " seconds)"
                );
            }

            description.append(".");

            if (i < events.size() - 1) {

                description.append(" ");
            }
        }

        return description.toString();
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

            boolean opened =
                    capture.open(
                            videoPath.toString(),
                            opencv_videoio.CAP_FFMPEG
                    );

            if (!opened
                    || !capture.isOpened()) {

                capture.release();

                opened =
                        capture.open(
                                videoPath.toString()
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
     * ================================================================
     * VIDEO FRAME
     * ================================================================
     */
    private static class VideoFrame {

        private final Path path;

        private final double timestamp;

        private VideoFrame(
                Path path,
                double timestamp
        ) {

            this.path = path;

            this.timestamp =
                    timestamp;
        }
    }
}