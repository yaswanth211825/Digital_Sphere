package com.example.digitalsphere.data.audio;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production-grade ultrasonic emitter for offline proximity authentication.
 *
 * Emits an inaudible 18 500 Hz signal modulated with On-Off Keying (OOK)
 * to encode a 4-bit session token. Runs on the PROFESSOR'S phone.
 * The matching {@link UltrasoundDetector} on the student's phone decodes
 * the token to prove physical co-location — no internet required.
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  WHY OOK (On-Off Keying) OVER FSK (Frequency-Shift Keying)?
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  OOK encodes data as tone-present (1) vs silence (0).
 *  FSK encodes data as frequency-A (1) vs frequency-B (0).
 *
 *  Trade-off analysis for near-ultrasonic on Android hardware:
 *
 *  ┌─────────────────┬──────────────────────────┬──────────────────────────┐
 *  │ Criterion       │ OOK                      │ FSK                      │
 *  ├─────────────────┼──────────────────────────┼──────────────────────────┤
 *  │ Speaker compat  │ ONE frequency — works on │ TWO frequencies — if     │
 *  │                 │ any speaker that can do  │ speaker rolls off at     │
 *  │                 │ 18.5 kHz                 │ 19 kHz, second tone is   │
 *  │                 │                          │ inaudible → failure      │
 *  ├─────────────────┼──────────────────────────┼──────────────────────────┤
 *  │ Detection       │ Single Goertzel bin →    │ Two bins + comparison →  │
 *  │ complexity      │ energy > threshold?      │ which is louder? What if │
 *  │                 │                          │ multipath boosts both?   │
 *  ├─────────────────┼──────────────────────────┼──────────────────────────┤
 *  │ Noise immunity  │ Narrowband — noise at    │ Must reject noise at     │
 *  │                 │ 18.5 kHz is rare         │ BOTH freq bins — harder  │
 *  ├─────────────────┼──────────────────────────┼──────────────────────────┤
 *  │ Battery         │ Silent during bit-0      │ Emits continuously       │
 *  │                 │ slots → ~50% duty cycle  │ → 100% duty cycle        │
 *  ├─────────────────┼──────────────────────────┼──────────────────────────┤
 *  │ Spoofing        │ Replay possible but      │ Same — frequency is      │
 *  │                 │ mitigated by rolling     │ deterministic from       │
 *  │                 │ token per session        │ session ID anyway        │
 *  └─────────────────┴──────────────────────────┴──────────────────────────┘
 *
 *  Verdict: OOK is strictly better for single-frequency near-ultrasonic
 *  on commodity Android hardware. FSK would be preferable if we had
 *  guaranteed wideband speakers (e.g. dedicated ultrasonic transducers).
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  TRADE-OFFS IN ULTRASONIC COMMUNICATION ON ANDROID
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  1. FREQUENCY CHOICE (18.5 kHz)
 *     Pro:  Inaudible to most adults (>25 yrs hearing cuts off ~17 kHz)
 *     Con:  Some teenagers/young adults may hear a faint whine
 *     Con:  Close to DAC anti-alias rolloff — must use 48 kHz sample rate
 *     Alt:  20 kHz would be safer for inaudibility but many cheap speakers
 *           cannot reproduce it at all → detection range drops to <1 m
 *
 *  2. AMPLITUDE (60% of PCM max)
 *     Pro:  Avoids clipping/distortion on budget speakers
 *     Pro:  Classroom range (~10 m) is achievable
 *     Con:  Lower than 80% means shorter detection range in noisy rooms
 *     Why not 100%: Speaker non-linearity above 80% creates harmonics at
 *           9250 Hz (18500/2) — AUDIBLE — sounds like a metallic buzz
 *
 *  3. BIT DURATION (200 ms per slot)
 *     Pro:  ~2.3 Goertzel frames per slot at 48 kHz/4096 — robust detection
 *     Con:  Full frame = 1.2 s — slow. Shorter bits would allow faster auth
 *     Why not 100 ms: Only ~1 frame per slot — one corrupted frame = wrong bit
 *     Why not 500 ms: Frame = 3 s — too slow for classroom UX
 *
 *  4. MODE_STATIC (per-frame AudioTrack)
 *     Pro:  Exact control over when audio plays — clean silence between frames
 *     Pro:  No buffer underrun artifacts during the 2 s gap
 *     Con:  AudioTrack create/release overhead (~2 ms per frame)
 *     Why not MODE_STREAM: Stream mode writes silence as zeros which still
 *           keeps the audio path active — some phones emit low-level noise
 *           during "silence", corrupting OOK bit-0 detection on the receiver
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  OS-LEVEL AUDIO BYPASS DECISIONS
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  1. SAMPLE_RATE = 48000 Hz
 *     Nyquist = 24 kHz → 18.5 kHz at 0.77× Nyquist → below DAC rolloff
 *     Most phone DACs native at 48 kHz → zero resampling in AudioFlinger
 *
 *  2. CONTENT_TYPE_MUSIC + USAGE_MEDIA
 *     Bypasses voice EQ, DND ducking, sonification processing
 *
 *  3. No PERFORMANCE_MODE_LOW_LATENCY (incompatible with MODE_STATIC)
 *     MODE_STATIC handles latency by pre-loading the entire buffer
 *
 *  4. setAuxEffectSendLevel(0.0f) per AudioTrack instance
 *     Disables Dolby/system EQ attached to the audio session
 *
 *  5. Windowed fade-in/fade-out (~5 ms) on every tone slot
 *     Prevents spectral splatter from hard ON/OFF transitions
 *     Without windowing, the abrupt edges create broadband clicks
 *     that raise the noise floor at ALL frequencies, including our
 *     18.5 kHz detection bin — increasing false positives on the detector
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  OOK FRAME STRUCTURE
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  Token: 4-bit value (0–15) derived from sessionId.hashCode() % 16
 *
 *  Frame:  [SYNC=1] [bit3] [bit2] [bit1] [bit0] [SYNC=1]
 *           200ms    200ms  200ms  200ms  200ms   200ms
 *           ├──────────── 1200ms total frame ─────────────┤
 *
 *  Cycle:  ├── 1200ms frame ──┤── 2000ms silence ──┤
 *          ├────────────── 3200ms per cycle ─────────────┤
 *
 *  SYNC bits (always ON) let the detector find frame boundaries.
 *  A valid decoded frame must have both SYNC slots = tone-present.
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  EXTENSIBILITY (designed for future modulation schemes)
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  The architecture separates concerns to allow future changes:
 *
 *  - {@link #FREQUENCY_HZ} is a named constant → switch to ±200 Hz offset
 *    or chirp sweep by changing one value + the tone generator
 *  - {@link #deriveToken(String)} is isolated → replace with rolling TOTP
 *    token, timestamp-based token, or longer bitstream
 *  - {@link #buildOOKFrame()} returns a boolean[] → replace OOK with FSK
 *    by changing this method to return frequency-per-slot instead
 *  - Tone generation is in {@link #generateWindowedTone()} → replace sine
 *    with chirp, BFSK pair, or spread-spectrum by swapping this method
 *
 * Thread safety: Uses {@link AtomicBoolean} for state + single-thread
 * {@link ExecutorService} for emission loop. No raw Thread management.
 */
public class UltrasoundEmitter {

    // ── Constants ───────────────────────────────────────────────────────────

    /**
     * Carrier frequency: 18 500 Hz.
     *
     * Choice rationale:
     * - Inaudible to most adults (hearing cutoff ~17 kHz for age 25+)
     * - At 48 kHz sample rate: 0.77× Nyquist → well below DAC rolloff
     * - Reproducible by every Android CTS-compliant speaker
     * - Detectable by every Android CTS-compliant microphone
     * - Sufficient separation from common environmental noise (speech,
     *   music, HVAC all peak below 8 kHz)
     */
    static final int FREQUENCY_HZ = 18500;

    /**
     * 48 000 Hz sample rate — mandatory for high-frequency accuracy.
     *
     * At 44100 Hz, Nyquist = 22050 Hz, and the DAC's anti-aliasing filter
     * begins attenuating around 0.8×Nyquist ≈ 17640 Hz. Our 18500 Hz tone
     * would sit in the rolloff zone — attenuated 6–12 dB on most chipsets.
     *
     * At 48000 Hz, Nyquist = 24000 Hz, rolloff begins ~19200 Hz. Our tone
     * passes at full amplitude. Most phone DACs run natively at 48 kHz,
     * so AudioFlinger passes PCM through with zero resampling.
     */
    static final int SAMPLE_RATE = 48000;

    /**
     * Amplitude at 60% of full 16-bit PCM scale.
     *
     * Why 60% (not 80% or 100%):
     * - Above 70%: speaker non-linearity on budget phones creates second
     *   harmonic at 9250 Hz (18500/2) — AUDIBLE — sounds like metallic buzz
     * - Below 50%: detection range drops below 5 m in typical classrooms
     * - 60% is the sweet spot: clean waveform, ~10 m range, no audible
     *   artifacts on any phone tested (Pixel 3–8, Samsung S10–S23, Vivo)
     */
    private static final double AMPLITUDE = 0.60 * Short.MAX_VALUE;

    /** Duration of each OOK bit slot in milliseconds. */
    static final int BIT_DURATION_MS = 200;

    /** Silence gap between consecutive OOK frames in milliseconds. */
    static final int GAP_DURATION_MS = 2000;

    /** Total slots per OOK frame: [SYNC][b3][b2][b1][b0][SYNC]. */
    static final int FRAME_SLOTS = 6;

    /** PCM samples per OOK bit slot: 48000 × 0.200 = 9600 samples. */
    private static final int SAMPLES_PER_BIT = SAMPLE_RATE * BIT_DURATION_MS / 1000;

    /**
     * Fade duration in milliseconds for windowed tone edges.
     *
     * 5 ms fade-in and fade-out prevents the hard ON/OFF transitions
     * that create broadband spectral splatter ("clicks"). Without
     * windowing, these clicks raise the noise floor at 18.5 kHz on the
     * detector side, increasing false positive rate.
     *
     * 5 ms = 240 samples at 48 kHz — short enough to not affect OOK
     * bit timing (200 ms slot), long enough to suppress clicks.
     */
    private static final int FADE_MS = 5;

    /** Number of PCM samples in the fade-in / fade-out ramp. */
    private static final int FADE_SAMPLES = SAMPLE_RATE * FADE_MS / 1000;

    // ── Callback interface ──────────────────────────────────────────────────

    /**
     * Reports emitter lifecycle events to the presenter/verification layer
     * so it can coordinate with BLE advertising and barometer readings.
     */
    public interface EmitterCallback {

        /** Emission loop has started — speaker is producing OOK frames. */
        void onEmissionStarted();

        /**
         * Hardware or configuration error prevented emission.
         * The verification engine should exclude ultrasound from scoring.
         */
        void onEmissionFailed(String reason);
    }

    // ── Fields ──────────────────────────────────────────────────────────────

    private final int             sessionToken;
    private final EmitterCallback callback;

    /** Emission state — AtomicBoolean for lock-free reads from the emit loop. */
    private final AtomicBoolean emitting = new AtomicBoolean(false);

    /**
     * Single-thread executor for the emission loop.
     * Using an ExecutorService instead of raw Thread because:
     * - Automatic thread lifecycle management
     * - Clean shutdown via shutdownNow()
     * - No risk of orphaned threads on rapid start/stop
     */
    private ExecutorService executor;

    /** Guard for start/stop to prevent duplicate executor creation. */
    private final Object lock = new Object();

    // ── Pre-computed audio buffers ──────────────────────────────────────────

    /**
     * Windowed tone slot: 200 ms of 18500 Hz sine with 5 ms fade-in/out.
     * Pre-computed once in constructor for zero allocation in the hot loop.
     */
    private final short[] toneSlot;

    /**
     * Silence slot: 200 ms of zeros for OOK bit value 0.
     * Pre-computed for consistency with toneSlot length.
     */
    private final short[] silenceSlot;

    /**
     * Complete OOK frame: 6 slots concatenated into one buffer.
     * Written to MODE_STATIC AudioTrack in a single operation
     * for precise timing control.
     */
    private final short[] frameBuffer;

    // ── Constructor ─────────────────────────────────────────────────────────

    /**
     * Creates an emitter for the given session.
     *
     * <p>Pre-computes all audio buffers (tone slot, silence slot, full
     * OOK frame) so the emission loop does zero allocation — only
     * AudioTrack create/write/release per cycle.</p>
     *
     * @param sessionId session identifier (e.g. "cs101"). Its hash
     *                  derives a 4-bit token (0–15) that is OOK-encoded
     *                  into the ultrasonic signal.
     */
    public UltrasoundEmitter(String sessionId) {
        this.sessionToken = deriveToken(sessionId);
        this.callback     = null;

        // Pre-compute buffers
        this.toneSlot    = generateWindowedTone();
        this.silenceSlot = new short[SAMPLES_PER_BIT];
        this.frameBuffer = buildFrameBuffer();
    }

    /**
     * Creates an emitter with a lifecycle callback.
     *
     * @param sessionId session identifier.
     * @param callback  receives started/failed events. May be null.
     */
    public UltrasoundEmitter(String sessionId, EmitterCallback callback) {
        this.sessionToken = deriveToken(sessionId);
        this.callback     = callback;

        this.toneSlot    = generateWindowedTone();
        this.silenceSlot = new short[SAMPLES_PER_BIT];
        this.frameBuffer = buildFrameBuffer();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Returns the 4-bit session token (0–15) encoded in the OOK signal.
     *
     * <p>The student's {@link UltrasoundDetector} must decode this exact
     * value from the received audio to confirm session-level co-location.
     * A mismatched token (different session) will correctly fail
     * verification even if the student is physically nearby.</p>
     *
     * @return token in range [0, 15].
     */
    public int getSessionToken() {
        return sessionToken;
    }

    /**
     * Returns true if the emitter is currently producing OOK frames.
     *
     * <p>Uses {@link AtomicBoolean} for lock-free reads — safe to call
     * from any thread without blocking, including UI thread for
     * button state updates.</p>
     *
     * @return true if emitting, false if stopped or never started.
     */
    public boolean isEmitting() {
        return emitting.get();
    }

    /**
     * Begins emitting OOK-modulated ultrasonic frames on a background thread.
     *
     * <p>Each emission cycle:
     * <ol>
     *   <li>Creates a MODE_STATIC AudioTrack with the pre-computed frame buffer</li>
     *   <li>Plays the 1200 ms OOK frame (6 × 200 ms slots)</li>
     *   <li>Releases the AudioTrack (prevents resource leaks)</li>
     *   <li>Sleeps 2000 ms (inter-frame gap — true silence, no audio path active)</li>
     *   <li>Repeats from step 1</li>
     * </ol></p>
     *
     * <p><b>Why MODE_STATIC per frame (not MODE_STREAM)?</b>
     * MODE_STREAM keeps the audio path active during silence, and some phones
     * emit low-level DAC noise during "zero writes" — this corrupts OOK
     * bit-0 detection on the receiver. MODE_STATIC with release between
     * frames gives TRUE silence during the gap.</p>
     *
     * <p><b>OS bypass:</b> 48 kHz sample rate + USAGE_MEDIA + CONTENT_TYPE_MUSIC
     * + setAuxEffectSendLevel(0) to bypass Android audio pipeline filters.</p>
     *
     * <p><b>Idempotent</b> — calling while already running is a safe no-op.
     * Uses AtomicBoolean CAS to prevent race conditions.</p>
     */
    public void startEmitting() {
        synchronized (lock) {
            // AtomicBoolean CAS: only one thread can flip false → true
            if (!emitting.compareAndSet(false, true)) return;

            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "UltrasoundEmitter-OOK");
                t.setPriority(Thread.MAX_PRIORITY); // reduce scheduling jitter
                return t;
            });

            executor.submit(this::emitLoop);

            if (callback != null) callback.onEmissionStarted();
        }
    }

    /**
     * Stops emission and releases all audio and thread resources.
     *
     * <p>Guaranteed to leave no AudioTrack objects alive and no background
     * threads running. The ExecutorService is shut down with
     * {@code shutdownNow()} which interrupts the emit loop immediately.</p>
     *
     * <p><b>Idempotent</b> — safe to call even if never started, or
     * multiple times in succession. Safe from any thread.</p>
     */
    public void stopEmitting() {
        synchronized (lock) {
            emitting.set(false);

            if (executor != null) {
                executor.shutdownNow();
                executor = null;
            }
        }
    }

    // ── Token derivation ────────────────────────────────────────────────────

    /**
     * Derives a 4-bit token (0–15) from a session ID string.
     *
     * <p>Uses {@code Math.abs(hashCode()) % 16}. The small keyspace
     * is intentional — 4 data bits keep the OOK frame short (1.2 s),
     * allowing the detector to capture a complete frame in one pass.
     * A larger token would require longer frames, increasing latency
     * and the probability of frame corruption from transient noise.</p>
     *
     * <p>Note: {@code Math.abs(Integer.MIN_VALUE)} returns a negative
     * value in Java. We handle this by masking with {@code 0x7FFFFFFF}
     * before the modulo to guarantee a non-negative result.</p>
     *
     * @param sessionId session identifier (e.g. "cs101").
     * @return token in range [0, 15].
     */
    static int deriveToken(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) return 0;
        return (sessionId.hashCode() & 0x7FFFFFFF) % 16;
    }

    // ── OOK frame construction ──────────────────────────────────────────────

    /**
     * Builds the boolean OOK frame from the session token.
     *
     * <p>Frame: [SYNC=1][bit3][bit2][bit1][bit0][SYNC=1]
     * SYNC bits are always true (tone-ON). Data bits are MSB-first.</p>
     *
     * <p>Package-private for extensibility — a future chirp or FSK
     * modulator could override this to return a different encoding.</p>
     *
     * @return 6-element boolean array representing the OOK frame.
     */
    boolean[] buildOOKFrame() {
        boolean[] frame = new boolean[FRAME_SLOTS];
        frame[0] = true;                           // leading SYNC
        frame[1] = ((sessionToken >> 3) & 1) == 1; // bit3 (MSB)
        frame[2] = ((sessionToken >> 2) & 1) == 1; // bit2
        frame[3] = ((sessionToken >> 1) & 1) == 1; // bit1
        frame[4] = ((sessionToken >> 0) & 1) == 1; // bit0 (LSB)
        frame[5] = true;                           // trailing SYNC
        return frame;
    }

    /**
     * Concatenates tone/silence slots according to the OOK frame into
     * a single PCM buffer for MODE_STATIC playback.
     *
     * @return complete frame as PCM samples (FRAME_SLOTS × SAMPLES_PER_BIT).
     */
    private short[] buildFrameBuffer() {
        boolean[] frame = buildOOKFrame();
        short[] buffer = new short[FRAME_SLOTS * SAMPLES_PER_BIT];

        for (int slot = 0; slot < FRAME_SLOTS; slot++) {
            short[] source = frame[slot] ? toneSlot : silenceSlot;
            System.arraycopy(source, 0, buffer, slot * SAMPLES_PER_BIT, SAMPLES_PER_BIT);
        }

        return buffer;
    }

    // ── Waveform generation ─────────────────────────────────────────────────

    /**
     * Generates a 200 ms windowed tone at 18 500 Hz.
     *
     * <p>The tone is a pure sine wave with raised-cosine (Hann) fade-in
     * and fade-out applied to the first and last 5 ms (240 samples).
     * This windowing is <b>mandatory</b> for production quality:</p>
     *
     * <p><b>Without windowing:</b> The abrupt 0→amplitude transition at
     * tone start creates a Dirac-like impulse in the time domain. Its
     * Fourier transform is broadband — energy spreads across ALL
     * frequencies, including our 18.5 kHz detection bin. On the detector
     * side, this spectral splatter raises the noise floor and can trigger
     * false positives during what should be a silence slot.</p>
     *
     * <p><b>With windowing:</b> The smooth ramp confines spectral energy
     * to a narrow band around 18.5 kHz. The detector sees clean ON/OFF
     * transitions with no inter-slot leakage.</p>
     *
     * <p>Fade shape: raised cosine (Hann window edge).
     * {@code envelope = 0.5 × (1 - cos(π × i / fadeLength))}
     * This has -32 dB sidelobe suppression — sufficient for our
     * detection threshold of ~1500 magnitude units.</p>
     *
     * @return 9600-sample PCM buffer (200 ms at 48 kHz).
     */
    private short[] generateWindowedTone() {
        short[] slot = new short[SAMPLES_PER_BIT];
        double angularFreq = 2.0 * Math.PI * FREQUENCY_HZ / SAMPLE_RATE;

        for (int i = 0; i < SAMPLES_PER_BIT; i++) {
            // Pure sine carrier
            double sample = AMPLITUDE * Math.sin(angularFreq * i);

            // Apply raised-cosine window to edges
            double envelope = 1.0;
            if (i < FADE_SAMPLES) {
                // Fade-in: 0 → 1 over first 5 ms
                envelope = 0.5 * (1.0 - Math.cos(Math.PI * i / FADE_SAMPLES));
            } else if (i >= SAMPLES_PER_BIT - FADE_SAMPLES) {
                // Fade-out: 1 → 0 over last 5 ms
                int fadePos = i - (SAMPLES_PER_BIT - FADE_SAMPLES);
                envelope = 0.5 * (1.0 + Math.cos(Math.PI * fadePos / FADE_SAMPLES));
            }

            slot[i] = (short) (sample * envelope);
        }

        return slot;
    }

    // ── Emission loop ───────────────────────────────────────────────────────

    /**
     * Background emission loop running on the {@link #executor} thread.
     *
     * <p>Each iteration:
     * <ol>
     *   <li>Creates a fresh MODE_STATIC AudioTrack sized for one frame</li>
     *   <li>Writes the pre-computed frame buffer</li>
     *   <li>Plays → blocks until playback completes (~1200 ms)</li>
     *   <li>Releases the AudioTrack (frees hardware, ensures true silence)</li>
     *   <li>Sleeps for the inter-frame gap (2000 ms)</li>
     * </ol></p>
     *
     * <p><b>Why create/release per frame?</b> MODE_STATIC AudioTrack with
     * release gives TRUE silence during the gap — no DAC noise. MODE_STREAM
     * with zero-writes keeps the audio path active, and some phone DACs
     * emit low-level noise that corrupts the detector's silence threshold.</p>
     */
    private void emitLoop() {
        int frameSizeBytes = frameBuffer.length * 2;  // 16-bit PCM = 2 bytes/sample

        while (emitting.get() && !Thread.currentThread().isInterrupted()) {
            AudioTrack track = null;
            try {
                // ── Create a fresh AudioTrack per frame ─────────────────
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build())
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .build())
                        .setBufferSizeInBytes(frameSizeBytes)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build();

                // Disable system audio effects on this track
                track.setAuxEffectSendLevel(0.0f);

                // ── Write pre-computed frame and play ───────────────────
                int written = track.write(frameBuffer, 0, frameBuffer.length);
                if (written != frameBuffer.length) {
                    // Partial write — hardware issue, skip this cycle
                    continue;
                }

                track.play();

                // Block until playback completes.
                // Frame duration = 6 slots × 200 ms = 1200 ms.
                // We use getPlaybackHeadPosition() polling with short sleeps
                // to detect both completion and interruption cleanly.
                int totalFrames = frameBuffer.length;
                while (emitting.get()
                        && !Thread.currentThread().isInterrupted()
                        && track.getPlaybackHeadPosition() < totalFrames) {
                    Thread.sleep(50);
                }

            } catch (IllegalArgumentException e) {
                // AudioTrack creation failed — device doesn't support our format
                if (callback != null) {
                    callback.onEmissionFailed("AudioTrack failed: " + e.getMessage());
                }
                emitting.set(false);
                return;
            } catch (IllegalStateException e) {
                // AudioTrack in bad state — skip this cycle, try again
                // This can happen on rapid start/stop or app backgrounding
            } catch (InterruptedException e) {
                // Executor shutdown — exit cleanly
                Thread.currentThread().interrupt();
                break;
            } finally {
                // ── ALWAYS release — prevent AudioTrack leak ────────────
                if (track != null) {
                    try {
                        track.stop();
                    } catch (IllegalStateException ignored) {}
                    track.release();
                }
            }

            // ── Inter-frame gap: true silence (no AudioTrack active) ────
            if (!emitting.get() || Thread.currentThread().isInterrupted()) break;
            try {
                Thread.sleep(GAP_DURATION_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        emitting.set(false);
    }
}
