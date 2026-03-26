package com.example.digitalsphere.data.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Records a short ambient audio clip and computes a compact spectral
 * fingerprint that summarises the acoustic environment.
 *
 * Both the professor's and student's devices run this simultaneously.
 * The resulting fingerprints are exchanged (via BLE manufacturer data or
 * a session-level sync) and fed into {@link AudioCorrelator} which
 * cross-correlates them. A high similarity score means both devices
 * were in the same acoustic environment — strong proof of co-location.
 *
 * Fingerprint algorithm:
 * The audio is split into overlapping time windows. For each window we
 * compute the energy in a fixed set of frequency sub-bands (a simplified
 * version of Mel-frequency analysis). The result is a compact float
 * array small enough to transmit over BLE but rich enough to distinguish
 * "same room" from "different room".
 *
 * Thread safety: {@link #record()} blocks the calling thread for the
 * recording duration. Call it from a background thread.
 */
public class AmbientAudioRecorder {

    // ── Constants ─────────────────────────────────────────────────────────

    /** Standard sample rate — matches UltrasoundEmitter/Detector. */
    private static final int SAMPLE_RATE = 44100;

    /** Recording duration in seconds — short enough to feel instant, long
     *  enough to capture a representative acoustic snapshot. */
    private static final int RECORD_DURATION_SECONDS = 3;

    /** Total PCM samples to capture: 3 s × 44 100 = 132 300 samples. */
    private static final int TOTAL_SAMPLES = SAMPLE_RATE * RECORD_DURATION_SECONDS;

    /**
     * Number of samples per analysis window. 2048 at 44 100 Hz gives
     * ~46 ms windows — enough temporal resolution to capture transient
     * room sounds (HVAC hum, projector fan, crowd murmur).
     */
    private static final int WINDOW_SIZE = 2048;

    /** Windows overlap by 50% — standard practice to avoid missing
     *  events that straddle a window boundary. */
    private static final int HOP_SIZE = WINDOW_SIZE / 2;

    /**
     * Frequency sub-band edges in Hz. Chosen to cover the range most
     * relevant for room-level acoustic fingerprinting:
     *   - 200–500 Hz: HVAC, low crowd murmur
     *   - 500–1000 Hz: speech fundamentals, projector hum
     *   - 1000–2000 Hz: upper speech harmonics
     *   - 2000–4000 Hz: high-frequency room reflections
     *
     * 4 bands × N windows = a fingerprint compact enough for BLE.
     */
    private static final int[] BAND_EDGES = {200, 500, 1000, 2000, 4000};

    /** Number of sub-bands = edges.length − 1. */
    private static final int NUM_BANDS = BAND_EDGES.length - 1;

    // ── Callback interface ────────────────────────────────────────────────

    /**
     * Delivers the computed fingerprint back to the caller.
     */
    public interface RecorderCallback {

        /**
         * Recording and fingerprint extraction succeeded.
         *
         * @param fingerprint a float array of band energies across all
         *                    time windows. Dimensions: [numWindows × NUM_BANDS],
         *                    flattened row-major. Feed directly into
         *                    {@link AudioCorrelator#correlate}.
         */
        void onFingerprintReady(float[] fingerprint);

        /**
         * Recording failed — most likely RECORD_AUDIO permission missing,
         * or the microphone is locked by another app (e.g. UltrasoundDetector).
         * The verification engine should exclude audio correlation.
         */
        void onRecordingFailed(String reason);
    }

    // ── Fields ────────────────────────────────────────────────────────────

    private final RecorderCallback callback;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * @param callback receives the fingerprint or an error. Must not be null.
     */
    public AmbientAudioRecorder(RecorderCallback callback) {
        this.callback = callback;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Captures {@value RECORD_DURATION_SECONDS} seconds of ambient audio,
     * computes a spectral fingerprint, and delivers it via callback.
     *
     * <b>Blocking call</b> — takes ~3 seconds. Must be called from a
     * background thread. The callback is fired on the same background
     * thread; the presenter must post to the main thread if needed.
     *
     * Requires {@code android.permission.RECORD_AUDIO}.
     */
    public void record() {
        int bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);

        if (bufferSize <= 0) {
            callback.onRecordingFailed("Device cannot allocate audio capture buffer.");
            return;
        }

        bufferSize = Math.max(bufferSize, TOTAL_SAMPLES * 2);

        AudioRecord recorder;
        try {
            recorder = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
        } catch (IllegalArgumentException | SecurityException e) {
            callback.onRecordingFailed("Microphone access failed: " + e.getMessage());
            return;
        }

        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            callback.onRecordingFailed("Microphone unavailable. Check RECORD_AUDIO permission.");
            return;
        }

        // ── Capture ───────────────────────────────────────────────────
        short[] pcm = new short[TOTAL_SAMPLES];
        recorder.startRecording();

        int totalRead = 0;
        while (totalRead < TOTAL_SAMPLES) {
            int read = recorder.read(pcm, totalRead, TOTAL_SAMPLES - totalRead);
            if (read < 0) {
                recorder.stop();
                recorder.release();
                callback.onRecordingFailed("Microphone read error (code " + read + ").");
                return;
            }
            totalRead += read;
        }

        recorder.stop();
        recorder.release();

        // ── Fingerprint ───────────────────────────────────────────────
        float[] fingerprint = computeFingerprint(pcm);
        callback.onFingerprintReady(fingerprint);
    }

    /**
     * Returns the number of frequency sub-bands per window.
     * Useful for {@link AudioCorrelator} to know the fingerprint layout.
     */
    public static int getNumBands() {
        return NUM_BANDS;
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Splits the PCM buffer into overlapping windows and computes the
     * energy in each frequency sub-band for each window.
     *
     * This is a simplified Mel-like spectral analysis that avoids a full
     * FFT. For each sub-band, we use the Goertzel algorithm at the band's
     * centre frequency — giving O(N × bands) complexity per window.
     *
     * @return flattened float array: [window0_band0, window0_band1, ...,
     *         window1_band0, ...]. Length = numWindows × NUM_BANDS.
     */
    private float[] computeFingerprint(short[] pcm) {
        int numWindows = (pcm.length - WINDOW_SIZE) / HOP_SIZE + 1;
        float[] fingerprint = new float[numWindows * NUM_BANDS];

        for (int w = 0; w < numWindows; w++) {
            int offset = w * HOP_SIZE;
            for (int b = 0; b < NUM_BANDS; b++) {
                int centreHz = (BAND_EDGES[b] + BAND_EDGES[b + 1]) / 2;
                fingerprint[w * NUM_BANDS + b] = bandEnergy(pcm, offset, WINDOW_SIZE, centreHz);
            }
        }

        return fingerprint;
    }

    /**
     * Computes the energy at a single frequency in a windowed PCM segment,
     * using the same Goertzel approach as {@link UltrasoundDetector}.
     */
    private float bandEnergy(short[] pcm, int offset, int length, int freqHz) {
        double k = (double) freqHz * length / SAMPLE_RATE;
        double w = 2.0 * Math.PI * k / length;
        double coeff = 2.0 * Math.cos(w);

        double s0 = 0.0, s1 = 0.0, s2 = 0.0;
        int end = Math.min(offset + length, pcm.length);

        for (int i = offset; i < end; i++) {
            s0 = pcm[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        return (float) Math.sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2);
    }
}
