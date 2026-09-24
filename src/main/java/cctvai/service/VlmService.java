package cctvai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Random;

/**
 * Turns a video frame (image bytes) into a natural-language description.
 *
 * Uses OpenAI's vision-capable model if an API key is configured.
 * Falls back to a lightweight offline heuristic captioner (brightness /
 * pixel-variance based) so the whole pipeline is runnable with zero API keys.
 *
 * Swap describeFrame's OpenAI branch for any other VLM (Gemini, Claude,
 * a local LLaVA server, etc.) — keep the same method signature.
 */
@Service
public class VlmService {

    private static final String PROMPT =
        "You are a CCTV monitoring assistant. Describe, in one or two plain " +
        "sentences, exactly what is happening in this com.cctvai.camera frame: how many " +
        "people/vehicles are present, what they are doing, and anything about " +
        "the scene worth noting. Be factual and concise. Do not speculate about intent.";

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Random random = new Random();

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.vision-model:gpt-4o-mini}")
    private String visionModel;

    public String describeFrame(byte[] imageBytes, String cameraId) {
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                return describeFrameOpenAI(imageBytes);
            } catch (Exception e) {
                return "[VLM error, fallback used: " + e.getMessage() + "] " + describeFrameMock(imageBytes);
            }
        }
        return describeFrameMock(imageBytes);
    }

    private String describeFrameOpenAI(byte[] imageBytes) throws IOException, InterruptedException {
        String b64 = Base64.getEncoder().encodeToString(imageBytes);

        var content = mapper.createArrayNode();
        content.add(mapper.createObjectNode().put("type", "text").put("text", PROMPT));

        var imageUrlNode = mapper.createObjectNode();
        imageUrlNode.put("url", "data:image/jpeg;base64," + b64);
        var imagePart = mapper.createObjectNode();
        imagePart.put("type", "image_url");
        imagePart.set("image_url", imageUrlNode);
        content.add(imagePart);

        var message = mapper.createObjectNode();
        message.put("role", "user");
        message.set("content", content);

        var body = mapper.createObjectNode();
        body.put("model", visionModel);
        body.put("max_tokens", 150);
        body.set("messages", mapper.createArrayNode().add(message));

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/chat/completions"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("OpenAI API error " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = mapper.readTree(response.body());
        return root.path("choices").get(0).path("message").path("content").asText().trim();
    }

    private static final List<String> NORMAL_TEMPLATES = List.of(
        "One or two people walking through the frame at a normal pace.",
        "The area appears empty with no significant movement.",
        "A person passes through the corridor and exits the frame.",
        "Routine foot traffic; nothing unusual observed.",
        "A vehicle drives through slowly and exits frame."
    );

    private static final List<String> BUSY_TEMPLATES = List.of(
        "Multiple people are gathered and moving quickly through the area.",
        "A group of several people is congregating in the frame.",
        "Fast movement detected involving more than one person."
    );

    /** Zero-dependency fallback: brightness + pixel-variance heuristic. Replace with a real VLM in production. */
    private String describeFrameMock(byte[] imageBytes) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(imageBytes));
            if (img == null) return "Unable to decode frame.";

            long sum = 0;
            long sumSq = 0;
            int w = img.getWidth(), h = img.getHeight();
            int sampleStep = Math.max(1, (w * h) / 5000); // sample for speed on large images
            int count = 0;

            for (int i = 0; i < w * h; i += sampleStep) {
                int x = i % w, y = i / w;
                if (y >= h) break;
                int rgb = img.getRGB(x, y);
                int gray = ((rgb >> 16 & 0xFF) + (rgb >> 8 & 0xFF) + (rgb & 0xFF)) / 3;
                sum += gray;
                sumSq += (long) gray * gray;
                count++;
            }

            double mean = count > 0 ? (double) sum / count : 0;
            double variance = count > 0 ? ((double) sumSq / count) - (mean * mean) : 0;
            double activity = Math.min(1.0, Math.sqrt(Math.max(0, variance)) / 80.0); // normalized proxy for "busyness"

            String desc = activity > 0.5
                ? BUSY_TEMPLATES.get(random.nextInt(BUSY_TEMPLATES.size()))
                : NORMAL_TEMPLATES.get(random.nextInt(NORMAL_TEMPLATES.size()));

            if (mean < 60) {
                desc += " Lighting conditions are low.";
            }
            return desc;
        } catch (IOException e) {
            return "Unable to process frame: " + e.getMessage();
        }
    }
}
