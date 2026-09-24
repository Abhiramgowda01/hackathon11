package cctvai.simulator;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.UUID;

/**
 * Simulates a live CCTV feed by posting frame images from a folder to the
 * backend at a fixed interval, looping continuously.
 *
 * Pure Java has no lightweight built-in video decoder, so first extract
 * frames from your video file with ffmpeg:
 *
 *   ffmpeg -i input.mp4 -vf fps=1/5 frames/frame_%04d.jpg
 *   (one frame every 5 seconds, matching the --interval below)
 *
 * Then run:
 *   java -cp target/classes com.cctvai.simulator.CameraSimulator \
 *       --dir frames --com.cctvai.camera-id front_gate --interval 5 --api-url http://localhost:8000
 */
public class CameraSimulator {

    public static void main(String[] args) throws Exception {
        String dir = getArg(args, "--dir", null);
        String cameraId = getArg(args, "--com.cctvai.camera-id", "camera_1");
        long intervalSeconds = Long.parseLong(getArg(args, "--interval", "5"));
        String apiUrl = getArg(args, "--api-url", "http://localhost:8000");

        if (dir == null) {
            System.err.println("Usage: --dir <folder of jpg frames> [--com.cctvai.camera-id id] [--interval seconds] [--api-url url]");
            System.exit(1);
        }

        File folder = new File(dir);
        File[] frames = folder.listFiles((d, name) -> name.toLowerCase().endsWith(".jpg") || name.toLowerCase().endsWith(".jpeg"));
        if (frames == null || frames.length == 0) {
            System.err.println("No .jpg frames found in " + dir);
            System.exit(1);
        }
        Arrays.sort(frames, Comparator.comparing(File::getName));

        HttpClient client = HttpClient.newHttpClient();
        System.out.printf("Streaming %d frames from '%s' as camera_id='%s' every %ds (looping)%n",
            frames.length, dir, cameraId, intervalSeconds);
        System.out.println("Press Ctrl+C to stop.");

        int i = 0;
        while (true) {
            File frame = frames[i % frames.length];
            try {
                String response = postFrame(client, apiUrl, cameraId, frame);
                System.out.println(response);
            } catch (Exception e) {
                System.err.println("Error posting frame: " + e.getMessage());
            }
            i++;
            Thread.sleep(Duration.ofSeconds(intervalSeconds).toMillis());
        }
    }

    private static String postFrame(HttpClient client, String apiUrl, String cameraId, File frame) throws IOException, InterruptedException {
        String boundary = "----CctvBoundary" + UUID.randomUUID();
        byte[] fileBytes = Files.readAllBytes(frame.toPath());

        String header = "--" + boundary + "\r\n" +
            "Content-Disposition: form-data; name=\"file\"; filename=\"" + frame.getName() + "\"\r\n" +
            "Content-Type: image/jpeg\r\n\r\n";
        String footer = "\r\n--" + boundary + "--\r\n";

        byte[] headerBytes = header.getBytes();
        byte[] footerBytes = footer.getBytes();
        byte[] body = new byte[headerBytes.length + fileBytes.length + footerBytes.length];
        System.arraycopy(headerBytes, 0, body, 0, headerBytes.length);
        System.arraycopy(fileBytes, 0, body, headerBytes.length, fileBytes.length);
        System.arraycopy(footerBytes, 0, body, headerBytes.length + fileBytes.length, footerBytes.length);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(apiUrl + "/process_frame?camera_id=" + cameraId))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .timeout(Duration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) {
            throw new IOException("HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private static String getArg(String[] args, String name, String defaultValue) {
        for (int i = 0; i < args.length - 1; i++) {
            if (args[i].equals(name)) return args[i + 1];
        }
        return defaultValue;
    }
}
