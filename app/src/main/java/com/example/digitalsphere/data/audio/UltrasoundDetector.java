package com.example.digitalsphere.data.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Listens for an ultrasonic tone emitted by the professor's
 * {@link UltrasoundEmitter} to confirm that the student is physically
 * in the same room — no internet required.
 *
 * How it works:
 * 1. Captures audio via {@link AudioRecord} at 44 100 Hz.
 * 2. Runs a simple Goertzel algorithm (single-bin DFT) on each frame,
 *    targeting the exact frequency derived from the session ID.
 * 3. If the energy at that frequency exceeds a threshold for
 *    {@code REQUIRED_CONSECUTIVE_HITS} consecutive frames, it reports
 *    "detected" — the student is co-located with the professor.
 *
 * Why Goertzel instead of a full FFT?
 * We only care about ONE frequency. Goertzel computes a single DFT bin
 * in O(N) time with no complex-number library, no bit-reversal, and no
 * butterfly operations. It's the textbook approach for DTMF detection
 * and works perfectly here for the same reason.
 *
 * Thread safety: {@link #start()} and {@link #stop()} are synchronized
 * internally, safe to call from any thread.
 */
public class UltrasoundDetector {

    // ── Constants ─────────────────────────────────────────────────────────

    /** Must match the emitter's sample rate so frequency bins align. */
    private static final int SAMPLE_RATE = 44100;

    /**
     * Number of samples per analysis frame. 4096 at 44 100 Hz gives a
     * frequency resolution of ~10.8 Hz — more than enough to distinguish
     * our target frequency from neighbours in the 18–20 kHz band.
     */
    private static final int FRAME_SIZE = 4096;

    /**
     * Goertzel magnitude threshold. Empirically set: a phone speaker
     * emitting at 80% amplitude produces magnitude ≈ 5 000–50 000 at
     * classroom range (1–10 m). 2 000 gives a safe margin above noise.
     */
    private static final double DETECTION_THRESHOLD = 2000.0;

    /**
     * How many consecutive frames must exceed the threshold before we
     * report "detected". Prevents single-frame noise spikes from
     * triggering a false positive.
     */
    private static final int REQUIRED_CONSECUTIVE_HITS = 3;

    // ── Callback interface ────────────────────────────────────────────────

    /**
     * Reports detection results back to the verification layer.
     */
    public interface DetectorCallback {

        /**
         * The target ultrasonic tone was detected with sufficient confidence.
         * This means the student's microphone can "hear" the professor's
         * speaker — strong evidence of physical co-location.
         *
         * @param magnitude the Goertzel magnitude at the target frequency.
         *                  Higher = stronger signal = closer proximity.
         */
        void onToneDetected(double magnitude);

        /**
         * The detector is running but has not (yet) found the tone.
         * The verification engine can use this to update a "searching…"
         * indicator or to timeout after a reasonable period.
         */
        void onToneNotDetected();

        /**
         * A hardware or permission error prevented detection.
         * Most common cause: RECORD_AUDIO permission not granted.
         */
        void onDetectionError(String reason);
    }

    // ── Fields ────────────────────────────────────────────────────────────

    private final int              targetFrequencyHz;
    private final DetectorCallback callback;

    private final Object  lock    = new Object();
    private AudioRecord   recorder;
    private Thread        listenThread;
    private volatile boolean running = false;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * @param sessionId session identifier — same string the professor used
     *                  to create the emitter. Ensures we listen for the
     *                  correct frequency. A mismatched session ID will never
     *                  detect the tone, which is by design — it prevents
     *                  cross-session spoofing.
     * @param callback  receives detection results. Must not be null.
     */
    public UltrasoundDetector(String sessionId, DetectorCallback callback) {
        this.targetFrequencyHz = UltrasoundEmitter.sessionToFrequency(sessionId);
        this.callback          = callback;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns the exact frequency this detector is listening for.
     * Useful for debug UI and for the verification engine to log
     * which frequency was expected vs. what was found.
     */
    public int getTargetFrequencyHz() {
        return targetFrequencyHz;
    }

    /**
     * Begins capturing audio and analysing frames on a background thread.
     *
     * Requires {@code android.permission.RECORD_AUDIO} — the caller
     * (Activity/Presenter) must ensure the permission is granted before
     * calling this. If not granted, {@code onDetectionError} fires.
     *
     * Idempotent — calling start() while already running is a no-op.
     */
    public void start() {
        synchronized (lock) {
            if (running) return;

            int bufferSize = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            if (bufferSize <= 0) {
                callback.onDetectionError("Device cannot allocate audio capture buffer.");
                return;
            }

            // Ensure buffer is at least FRAME_SIZE samples (× 2 bytes per sample).
            bufferSize = Math.max(bufferSize, FRAME_SIZE * 2);

            try {
                recorder = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
            } catch (IllegalArgumentException | SecurityException e) {
                callback.onDetectionError("AudioRecord init failed: " + e.getMessage());
                return;
            }

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = null;
                callback.onDetectionError("Microphone unavailable. Check RECORD_AUDIO permission.");
                return;
            }

            running = true;
            recorder.startRecording();

            listenThread = new Thread(this::detectLoop, "UltrasoundDetector");
            listenThread.start();
        }
    }

    /**
     * Stops listening and releases the microphone.
     *
     * Must be called when the session ends or the Activity is destroyed.
     * Failing to release keeps an exclusive lock on the mic, blocking
     * other apps (phone calls, voice assistants, etc.).
     *
     * Idempotent — safe to call even if never started.
     */
    public void stop() {
        synchronized (lock) {
            running = false;

            if (listenThread != null) {
                listenThread.interrupt();
                try { listenThread.join(500); } catch (InterruptedException ignored) {}
                listenThread = null;
            }

            if (recorder != null) {
                try {
                    recorder.stop();
                    recorder.release();
                } catch (IllegalStateException ignored) {}
                recorder = null;
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Capture-and-analyse loop running on {@link #listenThread}.
     *
     * Reads FRAME_SIZE samples, runs Goertzel at the target frequency,
     * and tracks consecutive hits. Reports "detected" only after
     * {@code REQUIRED_CONSECUTIVE_HITS} successive above-threshold frames
     * to filter out transient noise spikes.
     */
    private void detectLoop() {
        short[] buffer = new short[FRAME_SIZE];
        int consecutiveHits = 0;

        while (running && !Thread.currentThread().isInterrupted()) {
            int read = recorder.read(buffer, 0, FRAME_SIZE);
            if (read < FRAME_SIZE) continue;   // partial frame — skip

            double magnitude = goertzel(buffer, read, targetFrequencyHz);

            if (magnitude >= DETECTION_THRESHOLD) {
                consecutiveHits++;
                if (consecutiveHits >= REQUIRED_CONSECUTIVE_HITS) {
                    callback.onToneDetected(magnitude);
                    consecutiveHits = 0;     // reset so we can re-detect
                }
            } else {
                consecutiveHits = 0;
                callback.onToneNotDetected();
            }
        }
    }

    /**
     * Goertzel algorithm — computes the magnitude of a single DFT bin.
     *
     * Equivalent to running a full FFT and reading one bin, but O(N)
     * instead of O(N log N) and requires only three floating-point
     * variables — no arrays, no complex numbers.
     *
     * Reference: https://en.wikipedia.org/wiki/Goertzel_algorithm
     *
     * @param samples   PCM sample buffer (16-bit signed).
     * @param numSamples number of valid samples in the buffer.
     * @param targetHz  the frequency to measure.
     * @return magnitude (power) at the target frequency.
     */
    static double goertzel(short[] samples, int numSamples, int targetHz) {
        double k = (double) targetHz * numSamples / SAMPLE_RATE;
        double w = 2.0 * Math.PI * k / numSamples;
        double coeff = 2.0 * Math.cos(w);

        double s0 = 0.0;
        double s1 = 0.0;
        double s2 = 0.0;

        for (int i = 0; i < numSamples; i++) {
            s0 = samples[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        // Magnitude² = s1² + s2² − coeff·s1·s2
        return Math.sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2);
    }
}
