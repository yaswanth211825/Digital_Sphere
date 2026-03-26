package com.example.digitalsphere.data.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Emits an inaudible ultrasonic tone (18 000–20 000 Hz) via the device speaker
 * using {@link AudioTrack} in streaming mode.
 *
 * The professor's device runs this during an active session. The frequency is
 * derived deterministically from the session ID so every session produces a
 * unique tone — a student's {@link UltrasoundDetector} must find this exact
 * frequency to prove physical co-location.
 *
 * Design notes:
 * - 18–20 kHz is above the hearing range of most adults but well within the
 *   microphone/speaker bandwidth of every Android phone since minSdk 26.
 * - AudioTrack in MODE_STREAM lets us emit indefinitely without pre-allocating
 *   a massive buffer; we refill from a background thread.
 * - Amplitude is kept at 80% of full scale (0.8 × Short.MAX_VALUE) to avoid
 *   speaker distortion on cheap devices while staying loud enough for a
 *   classroom-range (~10 m) detection.
 *
 * Thread safety: {@link #start()} and {@link #stop()} are synchronized
 * internally, safe to call from any thread.
 */
public class UltrasoundEmitter {

    // ── Constants ─────────────────────────────────────────────────────────

    /** Bottom of our ultrasonic band — inaudible to most adults. */
    private static final int BASE_FREQUENCY_HZ = 18000;

    /** Top of our band — still reproducible by phone speakers. */
    private static final int MAX_FREQUENCY_HZ  = 20000;

    /** Frequency range we map session IDs into. */
    private static final int FREQUENCY_RANGE   = MAX_FREQUENCY_HZ - BASE_FREQUENCY_HZ;

    /** CD-quality sample rate — supported on every Android device since API 21. */
    private static final int SAMPLE_RATE = 44100;

    /**
     * Amplitude scaling factor. 0.8 × Short.MAX_VALUE keeps the signal
     * strong enough for classroom range while avoiding speaker clipping.
     */
    private static final double AMPLITUDE = 0.8 * Short.MAX_VALUE;

    // ── Callback interface ────────────────────────────────────────────────

    /**
     * Reports emitter lifecycle events back to the caller (BleManager or
     * verification layer) so it can coordinate with other signals.
     */
    public interface EmitterCallback {

        /** Emission has started — the speaker is now producing the tone. */
        void onEmissionStarted();

        /**
         * Something prevented emission (no audio focus, hardware error, etc.).
         * The verification engine should exclude ultrasound from its scoring.
         */
        void onEmissionFailed(String reason);
    }

    // ── Fields ────────────────────────────────────────────────────────────

    private final int              frequencyHz;
    private final EmitterCallback  callback;

    private final Object  lock    = new Object();
    private AudioTrack    track;
    private Thread        emitThread;
    private volatile boolean running = false;

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * @param sessionId session identifier (e.g. "cs101"). Its hash is mapped
     *                  into the 18–20 kHz band so each session gets a unique
     *                  tone — prevents cross-session false positives.
     * @param callback  receives started/failed events. Must not be null.
     */
    public UltrasoundEmitter(String sessionId, EmitterCallback callback) {
        this.frequencyHz = sessionToFrequency(sessionId);
        this.callback    = callback;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Returns the exact frequency this emitter will produce.
     * Exposed so the corresponding {@link UltrasoundDetector} on the student
     * side knows which frequency bin to look for after FFT.
     */
    public int getFrequencyHz() {
        return frequencyHz;
    }

    /**
     * Begins emitting the ultrasonic tone on a background thread.
     *
     * A dedicated thread fills the AudioTrack buffer with PCM samples in a
     * tight loop. This avoids blocking the main thread and ensures the tone
     * is continuous (no gaps that could cause missed detections).
     *
     * Idempotent — calling start() while already running is a no-op.
     */
    public void start() {
        synchronized (lock) {
            if (running) return;

            int bufferSize = AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);

            if (bufferSize <= 0) {
                callback.onEmissionFailed("Device cannot allocate audio buffer.");
                return;
            }

            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(bufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
            } catch (IllegalArgumentException e) {
                callback.onEmissionFailed("AudioTrack init failed: " + e.getMessage());
                return;
            }

            running = true;
            track.play();

            emitThread = new Thread(() -> emitLoop(bufferSize), "UltrasoundEmitter");
            emitThread.start();

            callback.onEmissionStarted();
        }
    }

    /**
     * Stops emission and releases all audio resources.
     *
     * Must be called when the session ends or the Activity is destroyed.
     * Failing to stop keeps the speaker active — draining battery and
     * potentially interfering with other audio on the device.
     *
     * Idempotent — safe to call even if never started.
     */
    public void stop() {
        synchronized (lock) {
            running = false;

            if (emitThread != null) {
                emitThread.interrupt();
                try { emitThread.join(500); } catch (InterruptedException ignored) {}
                emitThread = null;
            }

            if (track != null) {
                try {
                    track.stop();
                    track.release();
                } catch (IllegalStateException ignored) {
                    // Already stopped or released — harmless.
                }
                track = null;
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Maps a session ID string to a frequency in the 18 000–20 000 Hz band.
     *
     * Uses Math.abs(hashCode()) modulo the range so different sessions get
     * different frequencies. Collisions are theoretically possible but
     * irrelevant in practice — two professors would need to be in the same
     * room running sessions simultaneously with colliding hashes.
     */
    static int sessionToFrequency(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return BASE_FREQUENCY_HZ;
        int hash = Math.abs(sessionId.hashCode());
        return BASE_FREQUENCY_HZ + (hash % FREQUENCY_RANGE);
    }

    /**
     * Tight loop that generates sine-wave PCM samples and writes them to
     * the AudioTrack buffer. Runs on {@link #emitThread} until
     * {@code running} is set to false by {@link #stop()}.
     *
     * One sine cycle = SAMPLE_RATE / frequencyHz samples.
     * We pre-compute one full cycle, then write it repeatedly — cheaper
     * than calling Math.sin() on every sample in the hot loop.
     */
    private void emitLoop(int bufferSize) {
        // Pre-compute one full sine cycle as 16-bit PCM samples.
        int samplesPerCycle = SAMPLE_RATE / frequencyHz;
        short[] cycle = new short[samplesPerCycle];
        for (int i = 0; i < samplesPerCycle; i++) {
            double angle = 2.0 * Math.PI * i / samplesPerCycle;
            cycle[i] = (short) (AMPLITUDE * Math.sin(angle));
        }

        // Write the cycle buffer repeatedly until stopped.
        while (running && !Thread.currentThread().isInterrupted()) {
            int written = track.write(cycle, 0, cycle.length);
            if (written < 0) break;   // AudioTrack error — bail out silently
        }
    }
}
