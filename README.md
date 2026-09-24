# CCTV AI Monitor — Java (Spring Boot) version

Same architecture as the Python version, ported to Java:

```
Frame → VlmService (description) → AnomalyService (usual/unusual) → H2 database → REST API → dashboard
```

## Stack
- Java 17, Spring Boot 3.3, Maven
- H2 embedded database (file-based, zero setup)
- Plain HTML/JS dashboard (identical to the Python version — it just talks to REST endpoints)

## Setup

Requires JDK 17+ and Maven.

```bash
cd cctv-ai-java
mvn clean install
```

### (Optional) Add a real vision model + embeddings
Without an API key, the app still runs end-to-end using:
- a heuristic mock captioner (brightness/pixel-variance based) instead of a real VLM
- a hashing-trick bag-of-words vector instead of real semantic embeddings

For real AI descriptions and semantic anomaly cctvai.cctvai.detection:

```bash
export OPENAI_API_KEY=sk-...
```

To use a different vision/embedding provider, edit `VlmService.java` and
`EmbeddingService.java` — the rest of the pipeline (controller, anomaly
comparison, storage) doesn't care which model produced the values.

## Run the backend

```bash
mvn spring-boot:run
```

Backend runs on `http://localhost:8000`.

## Simulate a com.cctvai.camera feed

Pure Java has no lightweight video decoder, so first extract frames from
a video file using ffmpeg:

```bash
ffmpeg -i input.mp4 -vf fps=1/5 frames/frame_%04d.jpg
```

This gives you one frame every 5 seconds — match `--interval` below to whatever fps you chose.

Then run the simulator (after `mvn clean install`, classes are in `target/classes`):

```bash
java -cp target/classes simulator.cctvai.CameraSimulator \
  --dir frames --com.cctvai.camera-id front_gate --interval 5 --api-url http://localhost:8000
```

## Frontend

Open `src/main/resources/static` in a browser (or serve it: `python -m http.server 5500` from the `frontend/` folder). It polls the backend every 5 seconds and highlights flagged anomalies in red.

## Tuning anomaly sensitivity
In `AnomalyService.java`:
- `SIMILARITY_THRESHOLD` (default 0.45) — lower = stricter (more gets flagged as unusual)
- `MIN_HISTORY_BEFORE_FLAGGING` (default 8) — how many "normal" events a com.cctvai.camera needs before flagging starts
- `HISTORY_SIZE` (default 50) — how much recent history counts as the "usual" baseline

## Notes / next steps for production
- Add authentication before exposing the API beyond localhost.
- Add a lightweight motion/object-cctvai.cctvai.detection pre-filter so the VLM isn't called on every frame (cost control) — e.g. via JavaCV/OpenCV bindings if you want real-time video decoding in Java instead of the ffmpeg-based simulator.
- Swap H2 for Postgres/MySQL for multi-com.cctvai.camera, multi-day scale (just change `spring.datasource.*` in `application.properties`).
- Add per-location/time-of-day baselines (e.g., "usual" at 2am ≠ "usual" at 2pm) — currently baselines are per-com.cctvai.camera only.
- Check local surveillance/privacy regulations before deploying — storing AI-generated descriptions of people's activity has real legal implications.
