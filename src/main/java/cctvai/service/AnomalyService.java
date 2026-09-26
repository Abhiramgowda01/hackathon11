package cctvai.service;

import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides whether a new description is "usual" or "unusual" for a given
 * com.cctvai.camera by comparing its embedding to that com.cctvai.camera's recent history
 * (the rolling "baseline" of normal activity), using cosine similarity.
 * If the new description doesn't resemble ANY recent normal activity
 * (max similarity below a threshold), it's flagged as unusual.
 * Anomalies are not added to the baseline, so they don't pollute "normal".
 */
@Service
public class AnomalyService {

    private static final int HISTORY_SIZE = 50;
    private static final double SIMILARITY_THRESHOLD = 0.45;
    private static final int MIN_HISTORY_BEFORE_FLAGGING = 8;

    private final EmbeddingService embeddingService;
    private final Map<String, Deque<double[]>> baselines = new ConcurrentHashMap<>();

    public AnomalyService(EmbeddingService embeddingService) {
        this.embeddingService = embeddingService;
    }

    public record Result(boolean isAnomaly, Double maxSimilarity, int baselineSize) {}

    public synchronized Result evaluate(String cameraId, String description) {
        double[] embedding = embeddingService.embed(description);
        Deque<double[]> history = baselines.computeIfAbsent(cameraId, k -> new ArrayDeque<>());

        if (history.size() < MIN_HISTORY_BEFORE_FLAGGING) {
            addToHistory(history, embedding);
            return new Result(false, null, history.size());
        }

        double maxSim = Double.NEGATIVE_INFINITY;
        for (double[] past : history) {
            maxSim = Math.max(maxSim, cosineSimilarity(embedding, past));
        }

        boolean isAnomaly = maxSim < SIMILARITY_THRESHOLD;
        if (!isAnomaly) {
            addToHistory(history, embedding);
        }

        return new Result(isAnomaly, round(maxSim), history.size());
    }

    public void reset(String cameraId) {
        baselines.remove(cameraId);
    }

    private void addToHistory(Deque<double[]> history, double[] embedding) {
        history.addLast(embedding);
        while (history.size() > HISTORY_SIZE) {
            history.removeFirst();
        }
    }

    private double cosineSimilarity(double[] a, double[] b) {
        double dot = 0, normA = 0, normB = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        double denom = Math.sqrt(normA) * Math.sqrt(normB);
        return denom < 1e-8 ? 0 : dot / denom;
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
