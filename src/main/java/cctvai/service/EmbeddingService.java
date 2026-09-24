package cctvai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

/**
 * Converts a description into a vector so it can be compared for similarity.
 *
 * Uses OpenAI's embeddings API if a key is configured (accurate, semantic).
 * Falls back to a simple hashing-trick bag-of-words vector (offline, no
 * dependencies) so the whole pipeline runs without any API key — it is
 * cruder but still lets similar wording map to similar vectors.
 */
@Service
public class EmbeddingService {

    private static final int HASH_DIMENSIONS = 128;

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${openai.api-key:}")
    private String apiKey;

    @Value("${openai.embedding-model:text-embedding-3-small}")
    private String embeddingModel;

    public double[] embed(String text) {
        if (apiKey != null && !apiKey.isBlank()) {
            try {
                return embedOpenAI(text);
            } catch (Exception e) {
                return embedHashing(text); // silent fallback so pipeline never breaks
            }
        }
        return embedHashing(text);
    }

    private double[] embedOpenAI(String text) throws Exception {
        var body = mapper.createObjectNode();
        body.put("model", embeddingModel);
        body.put("input", text);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create("https://api.openai.com/v1/embeddings"))
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .timeout(Duration.ofSeconds(20))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
            .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new RuntimeException("Embeddings API error " + response.statusCode() + ": " + response.body());
        }
        JsonNode root = mapper.readTree(response.body());
        JsonNode vecNode = root.path("data").get(0).path("embedding");
        double[] vec = new double[vecNode.size()];
        for (int i = 0; i < vecNode.size(); i++) vec[i] = vecNode.get(i).asDouble();
        return vec;
    }

    /** Hashing-trick bag-of-words embedding: crude, but consistent and dependency-free. */
    private double[] embedHashing(String text) {
        double[] vec = new double[HASH_DIMENSIONS];
        String[] words = text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9\\s]", "").split("\\s+");
        for (String word : words) {
            if (word.isBlank()) continue;
            int bucket = Math.floorMod(word.hashCode(), HASH_DIMENSIONS);
            vec[bucket] += 1.0;
        }
        normalize(vec);
        return vec;
    }

    private void normalize(double[] vec) {
        double norm = 0;
        for (double v : vec) norm += v * v;
        norm = Math.sqrt(norm);
        if (norm > 1e-8) {
            for (int i = 0; i < vec.length; i++) vec[i] /= norm;
        }
    }
}
