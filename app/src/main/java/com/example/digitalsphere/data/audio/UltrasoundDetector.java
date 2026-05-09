package com.example.digitalsphere.data.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;
import com.example.digitalsphere.data.audio.adaptive.AdaptiveUltrasoundDetector;
import com.example.digitalsphere.data.audio.adaptive.FrequencyEnergyAnalyzer;
import com.example.digitalsphere.data.audio.adaptive.UltrasoundFrameCodec;
import com.example.digitalsphere.data.audio.adaptive.UltrasoundCapabilities;
import com.example.digitalsphere.data.audio.adaptive.UltrasoundSessionConfig;
import com.example.digitalsphere.data.sensor.DiagLogger;

/**
 * Listens for the professor's OOK-modulated ultrasonic signal and decodes
 * the 4-bit session token to confirm physical co-location.
 *
 * Runs on the STUDENT'S phone during a scan.
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  OS-LEVEL ADC / MICROPHONE BYPASS DECISIONS
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  PROBLEM: Android's audio input pipeline is designed for VOICE (300–3400 Hz).
 *  Our signal is at 18 500 Hz. Without bypasses, the OS will DESTROY it:
 *
 *  ┌──────────────────────────────────────────────────────────────────┐
 *  │  Microphone hardware            → OK (most phone mics go to 22 kHz)  │
 *  │  ADC anti-aliasing filter       → OK at 48 kHz (Nyquist=24 kHz)     │
 *  │  NoiseSuppressor                → KILLS non-voice frequencies        │
 *  │  AcousticEchoCanceler           → KILLS repeated tones               │
 *  │  AutomaticGainControl           → Pumps gain, distorts OOK edges     │
 *  │  AudioSource.MIC processing     → Applies all of the above           │
 *  └──────────────────────────────────────────────────────────────────┘
 *
 *  FIXES APPLIED HERE:
 *
 *  1. AudioSource.UNPROCESSED (API 24+, our minSdk=26 ✓)
 *     → Raw ADC samples with ZERO processing
 *     → Bypasses the entire voice enhancement pipeline
 *     → Falls back to AudioSource.MIC if UNPROCESSED unavailable
 *
 *  2. SAMPLE_RATE = 48000 Hz (matches emitter)
 *     → Nyquist = 24 000 Hz → 18.5 kHz is at 0.77 Nyquist
 *     → ADC anti-alias filter passes our tone cleanly
 *     → At 44100 Hz, the anti-alias rolloff would attenuate it
 *
 *  3. Explicit NoiseSuppressor.setEnabled(false)
 *     → Even with UNPROCESSED, some OEMs (Samsung, Xiaomi) still
 *       attach a noise suppressor. We force-disable it by AudioSession ID.
 *
 *  4. Explicit AcousticEchoCanceler.setEnabled(false)
 *     → Our OOK frame repeats every 3.2 seconds — the AEC would learn
 *       the pattern and cancel it as an "echo"
 *
 *  5. Explicit AutomaticGainControl.setEnabled(false)
 *     → AGC adjusts gain based on signal level. During silence slots
 *       (bit 0), AGC pumps gain up; when the tone returns (bit 1),
 *       the first few ms are clipped. This corrupts OOK edges.
 *
 *  6. PERFORMANCE_MODE_LOW_LATENCY on AudioRecord
 *     → Uses the fast capture path → fewer intermediate buffers →
 *       less chance of resampling in AudioFlinger
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  DETECTION ALGORITHM
 * ══════════════════════════════════════════════════════════════════════════
 *
 *  1. Capture audio in FRAME_SIZE chunks (4096 samples at 48 kHz ≈ 85ms)
 *  2. Run Goertzel at 18500 Hz on each frame → magnitude
 *  3. Threshold: magnitude > DETECTION_THRESHOLD → "tone ON", else "OFF"
 *  4. Accumulate ON/OFF pattern into a 6-slot OOK frame decoder
 *  5. Valid frame = [ON][?][?][?][?][ON] (leading + trailing SYNC)
 *  6. Extract 4-bit token from middle 4 slots
 *  7. If token matches session → onTokenDecoded(token)
 *
 *  Goertzel is used (not FFT) because we need energy at exactly ONE
 *  frequency. Goertzel is O(N) per frame with 3 float variables.
 *  A full FFT would be O(N log N) and waste computation on 2047
 *  frequency bins we don't care about.
 *
 * Thread safety: {@link #start()} and {@link #stop()} are synchronized.
 */
public class UltrasoundDetector {

    // ── Constants ───────────────────────────────────────────────────────────

    /** Must match emitter's sample rate for correct Goertzel frequency alignment. */
    static final int SAMPLE_RATE = 48000;

    /**
     * Samples per analysis frame. 4096 at 48 kHz = 85.3ms per frame.
     * Each OOK bit slot is 200ms ≈ 2.3 frames, giving us ~2 detection
     * opportunities per bit — enough to tolerate one corrupted frame.
     *
     * Frequency resolution = 48000/4096 ≈ 11.7 Hz per bin.
     * Our 18500 Hz target lands cleanly in bin 1585.
     */
    static final int FRAME_SIZE = 4096;

    /**
     * Goertzel magnitude threshold for "tone present".
     *
     * Empirically determined (single-frequency, full amplitude):
     *   - Phone speaker at 80% amp, 1–3m range: magnitude 10000–80000
     *   - Phone speaker at 80% amp, 5–10m range: magnitude 2000–15000
     *   - Ambient room noise at 18.5 kHz:        magnitude 50–500
     *
     * Dual-frequency emission (v2.2+): each component is emitted at 50%
     * amplitude. Goertzel energy scales with amplitude², so each component's
     * magnitude is ~50% of the single-frequency value. Threshold is halved
     * from 1500 → 750 to maintain the same effective detection range.
     * Noise floor (50–500) stays well below 750 — false-positive risk unchanged.
     */
    static final double DETECTION_THRESHOLD = 750.0;
    private static final int FREQUENCY_HZ_FALLBACK = 17500;
    private static final double CONFIDENCE_MAX_RATIO = 8.0;

    /**
     * Number of OOK slots per frame: [SYNC][b3][b2][b1][b0][SYNC] = 6.
     */
    private static final int OOK_FRAME_SLOTS = 6;

    /**
     * Frames per OOK bit slot. Each slot = 200ms, each frame ≈ 85ms.
     * So ~2.3 frames per slot. We use 2 consecutive frames to decide
     * if a slot is ON or OFF (majority vote).
     */
    private static final int FRAMES_PER_SLOT = 2;

    /**
     * Number of consecutive decodes of the expected token required before
     * we report success. This filters out single-window misalignment blips
     * and most silence false positives without requiring a full redesign
     * of the timing model.
     */
    private static final int REQUIRED_CONSECUTIVE_MATCHES = 1;

    /**
     * The 6-slot OOK frame spans ~14.06 analysis frames at 48 kHz / 4096.
     * We keep a slightly larger history and try a few plausible 2/3-frame
     * slot partitions instead of forcing everything into exactly 12 frames.
     */
    private static final int OOK_HISTORY_FRAMES = 16;
    private static final int MIN_FRAMES_FOR_DECODE = 14;
    private static final int[][] SLOT_FRAME_PATTERNS = {
            {2, 2, 3, 2, 2, 3},
            {2, 3, 2, 2, 3, 2},
            {3, 2, 2, 3, 2, 2}
    };
    private static final int ADAPTIVE_TOKEN_BITS = 4;
    private static final float ADAPTIVE_CONFIDENCE_FLOOR = 0.30f;
    private static final int ADAPTIVE_PHASE_DIVISIONS = 4;

    // ── Callback interface ──────────────────────────────────────────────────

    /**
     * Reports detection results to the verification layer.
     */
    public interface DetectorCallback {

        /**
         * A valid OOK frame was decoded and the session token extracted.
         *
         * @param token the 4-bit session token (0–15) decoded from the
         *              ultrasonic signal. The verification engine compares
         *              this against the expected token from the session ID.
         * @param magnitude average Goertzel magnitude during detection.
         *                  Higher = stronger signal = closer proximity.
         */
        void onTokenDecoded(int token, double magnitude);

        /**
         * The detector is running but has not decoded a valid token yet.
         * Called once per analysis frame so the UI can show "searching…"
         */
        void onSearching();

        /**
         * Hardware, permission, or configuration error.
         * Most common: RECORD_AUDIO permission not granted.
         */
        void onDetectionError(String reason);
    }

    // ── Fields ──────────────────────────────────────────────────────────────

    private final int              expectedToken;
    private final DetectorCallback callback;
    private final int              audioSource;
    private final UltrasoundSessionConfig sessionConfig;

    private final Object  lock    = new Object();
    private AudioRecord   recorder;
    private Thread        listenThread;
    private volatile boolean running = false;

    // OS bypass: audio effect handles — must be kept alive to stay disabled
    private NoiseSuppressor         noiseSuppressor;
    private AcousticEchoCanceler    echoCanceler;
    private AutomaticGainControl    gainControl;

    // ── Constructor ─────────────────────────────────────────────────────────

    /**
     * @param sessionId same session ID the professor used to create the
     *                  emitter. Derives the expected 4-bit token internally.
     * @param callback  receives decoded tokens or errors. Must not be null.
     */
    public UltrasoundDetector(String sessionId, DetectorCallback callback) {
        this(sessionId, callback, MediaRecorder.AudioSource.UNPROCESSED);
    }

    public UltrasoundDetector(String sessionId, DetectorCallback callback, UltrasoundCapabilities capabilities) {
        // Always attempt UNPROCESSED regardless of capability declaration.
        // PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED=false does not mean the
        // hardware lacks near-ultrasound ability — it only means the OEM did
        // not declare it. start() will fall back if construction fails.
        this(sessionId, callback, MediaRecorder.AudioSource.UNPROCESSED);
    }

    public UltrasoundDetector(String sessionId, DetectorCallback callback, int audioSource) {
        this(sessionId, UltrasoundEmitter.deriveToken(sessionId), callback, audioSource, null);
    }

    public UltrasoundDetector(String sessionId,
                              int expectedToken,
                              DetectorCallback callback,
                              UltrasoundCapabilities capabilities,
                              UltrasoundSessionConfig sessionConfig) {
        // Always attempt UNPROCESSED first regardless of capability declaration.
        this(sessionId, expectedToken, callback, MediaRecorder.AudioSource.UNPROCESSED, sessionConfig);
    }

    public UltrasoundDetector(String sessionId,
                              int expectedToken,
                              DetectorCallback callback,
                              int audioSource,
                              UltrasoundSessionConfig sessionConfig) {
        this.expectedToken = expectedToken >= 0 ? expectedToken : UltrasoundEmitter.deriveToken(sessionId);
        this.callback = callback;
        this.audioSource = audioSource;
        this.sessionConfig = sessionConfig;
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /** Returns the expected 4-bit token this detector is looking for. */
    public int getExpectedToken() {
        return expectedToken;
    }

    /** Returns the carrier frequency being detected (always 18500 Hz). */
    public int getTargetFrequencyHz() {
        return sessionConfig != null ? sessionConfig.getF1() : UltrasoundEmitter.FREQUENCY_HZ;
    }

    /**
     * Begins capturing audio and decoding OOK frames on a background thread.
     *
     * <p>Requires {@code android.permission.RECORD_AUDIO}. The caller must
     * ensure permission is granted before calling this.</p>
     *
     * <p><b>OS bypass:</b> Uses AudioSource.UNPROCESSED + 48 kHz sample rate
     * + explicit disable of NoiseSuppressor, AcousticEchoCanceler, and
     * AutomaticGainControl to ensure the raw 18.5 kHz tone reaches
     * the Goertzel detector unmodified.</p>
     *
     * <p>Idempotent — calling while already running is a no-op.</p>
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

            // Ensure buffer holds at least one full analysis frame
            bufferSize = Math.max(bufferSize, FRAME_SIZE * 2);

            // 3-tier fallback: UNPROCESSED → VOICE_RECOGNITION → MIC
            // UNPROCESSED gives raw ADC with no OEM processing — critical for
            // near-ultrasound detection. PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED=false
            // does not reliably predict construction failure on Qualcomm/MTK hardware.
            int[] sourcePriority = {
                    MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC
            };
            IllegalArgumentException lastException = null;
            for (int source : sourcePriority) {
                try {
                    recorder = new AudioRecord.Builder()
                            .setAudioSource(source)
                            .setAudioFormat(new AudioFormat.Builder()
                                    .setSampleRate(SAMPLE_RATE)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build())
                            .setBufferSizeInBytes(bufferSize)
                            .build();
                    DiagLogger.log("ULTRA_AUDIO_SOURCE",
                            "requested=" + audioSource + " actual=" + source, "", "OPENED");
                    break;
                } catch (IllegalArgumentException | SecurityException e) {
                    DiagLogger.log("ULTRA_AUDIO_SOURCE",
                            "source=" + source + " failed=" + e.getMessage(), "", "FALLBACK");
                    if (e instanceof IllegalArgumentException) lastException = (IllegalArgumentException) e;
                }
            }
            if (recorder == null) {
                callback.onDetectionError("Microphone access failed: " +
                        (lastException != null ? lastException.getMessage() : "all sources rejected"));
                return;
            }

            if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
                recorder.release();
                recorder = null;
                callback.onDetectionError(
                        "Microphone unavailable. Check RECORD_AUDIO permission.");
                return;
            }

            // OS BYPASS #3, #4, #5: Force-disable audio effects by session ID.
            // Even with UNPROCESSED source, some OEMs (Samsung One UI, MIUI)
            // still attach processing to the audio session. We kill each one
            // explicitly using the AudioRecord's session ID.
            disableAudioEffects(recorder.getAudioSessionId());

            running = true;
            recorder.startRecording();

            listenThread = new Thread(
                    sessionConfig != null ? this::detectLoopAdaptive : this::detectLoop,
                    sessionConfig != null ? "UltrasoundDetector-FSK" : "UltrasoundDetector-OOK");
            listenThread.start();
        }
    }

    /**
     * Stops detection and releases the microphone + audio effects.
     *
     * <p>Must be called when the scan ends or Activity is destroyed.
     * Failing to release keeps an exclusive lock on the mic.</p>
     *
     * <p>Idempotent — safe to call even if never started.</p>
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

            // Release audio effect objects to free native resources
            releaseAudioEffects();
        }
    }

    // ── OS Bypass: Audio Effect Suppression ─────────────────────────────────

    /**
     * Explicitly disables all three audio processing effects that Android
     * may attach to the recording session, even with AudioSource.UNPROCESSED.
     *
     * <p>Each effect is created for the given audioSessionId, disabled, and
     * kept alive as a field — releasing it would re-enable the system default.
     * We release them in {@link #stop()}.</p>
     *
     * <p>If a particular effect is not available on this device, we skip it
     * silently — the effect was never applied, so there's nothing to disable.</p>
     */
    private void disableAudioEffects(int audioSessionId) {
        // OS BYPASS #3: NoiseSuppressor — treats 18.5 kHz as noise, kills it
        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(false);
                }
            } catch (Exception ignored) {} // Some OEMs throw on create()
        }

        // OS BYPASS #4: AcousticEchoCanceler — treats repeated OOK frames as echo
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId);
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(false);
                }
            } catch (Exception ignored) {}
        }

        // OS BYPASS #5: AutomaticGainControl — pumps gain in silence slots,
        // clips tone slots, corrupts OOK bit boundaries
        if (AutomaticGainControl.isAvailable()) {
            try {
                gainControl = AutomaticGainControl.create(audioSessionId);
                if (gainControl != null) {
                    gainControl.setEnabled(false);
                }
            } catch (Exception ignored) {}
        }
    }

    /** Releases audio effect native resources. */
    private void releaseAudioEffects() {
        if (noiseSuppressor != null) {
            try { noiseSuppressor.release(); } catch (Exception ignored) {}
            noiseSuppressor = null;
        }
        if (echoCanceler != null) {
            try { echoCanceler.release(); } catch (Exception ignored) {}
            echoCanceler = null;
        }
        if (gainControl != null) {
            try { gainControl.release(); } catch (Exception ignored) {}
            gainControl = null;
        }
    }

    // ── Detection Loop ──────────────────────────────────────────────────────

    /**
     * Capture-and-decode loop on {@link #listenThread}.
     *
     * <p>Reads frames, runs Goertzel, and feeds ON/OFF decisions into an
     * OOK frame decoder. A valid frame is [ON][?][?][?][?][ON] — the
     * leading and trailing SYNC bits must both be detected.</p>
     */
    private void detectLoop() {
        short[] buffer = new short[FRAME_SIZE];

        // Ring buffer of ON/OFF decisions for OOK frame decoding.
        // We keep 16 frames so the decoder can search realistic 14-frame
        // partitions of the 1.2 s OOK pattern.
        int totalFramesPerOOK = OOK_HISTORY_FRAMES;
        boolean[] frameHistory = new boolean[totalFramesPerOOK];
        int frameIndex = 0;
        double magnitudeSum = 0;
        int magnitudeCount = 0;
        int consecutiveExpectedMatches = 0;

        while (running && !Thread.currentThread().isInterrupted()) {
            int read = recorder.read(buffer, 0, FRAME_SIZE);
            if (read < FRAME_SIZE) continue;

            double mag1 = goertzel(buffer, read, UltrasoundEmitter.FREQUENCY_HZ);
            double mag2 = goertzel(buffer, read, FREQUENCY_HZ_FALLBACK);
            boolean tonePresent = (mag1 >= DETECTION_THRESHOLD) || (mag2 >= DETECTION_THRESHOLD);

            // Store in ring buffer
            frameHistory[frameIndex % totalFramesPerOOK] = tonePresent;
            if (tonePresent) {
                magnitudeSum += Math.max(mag1, mag2);
                magnitudeCount++;
            }
            frameIndex++;

            // Need at least a full OOK frame's worth of data
            if (frameIndex < MIN_FRAMES_FOR_DECODE) {
                callback.onSearching();
                continue;
            }

            // Try to decode an OOK frame from the last 12 frames
            int decoded = tryDecodeFrame(frameHistory, frameIndex, totalFramesPerOOK);
            if (decoded >= 0) {
                if (decoded == expectedToken) {
                    consecutiveExpectedMatches++;
                    if (consecutiveExpectedMatches >= REQUIRED_CONSECUTIVE_MATCHES) {
                        double avgMag = magnitudeCount > 0 ? magnitudeSum / magnitudeCount : 0;
                        callback.onTokenDecoded(decoded, avgMag);
                        // Reset counters after successful decode of the expected token.
                        magnitudeSum = 0;
                        magnitudeCount = 0;
                        frameIndex = 0;
                        consecutiveExpectedMatches = 0;
                    } else {
                        callback.onSearching();
                    }
                } else {
                    // Ignore decodes for the wrong token. On real hardware these
                    // are usually frame-alignment artifacts, not valid room locks.
                    consecutiveExpectedMatches = 0;
                    callback.onSearching();
                }
            } else {
                consecutiveExpectedMatches = 0;
                callback.onSearching();
            }
        }
    }

    private void detectLoopAdaptive() {
        if (sessionConfig == null) {
            callback.onDetectionError("Adaptive ultrasound configuration missing.");
            return;
        }

        int samplesPerSymbol = sessionConfig.getSamplesPerSymbol();
        int frameSymbols = (sessionConfig.getPreambleBitCount()
                + ADAPTIVE_TOKEN_BITS
                + UltrasoundFrameCodec.CRC_BITS) * sessionConfig.getRepeatCount();
        int frameSamples = frameSymbols * samplesPerSymbol;
        int phaseStep = Math.max(1, samplesPerSymbol / ADAPTIVE_PHASE_DIVISIONS);
        int historyCapacity = frameSamples + samplesPerSymbol;
        short[] history = new short[historyCapacity];
        short[] readBuffer = new short[Math.max(samplesPerSymbol, FRAME_SIZE)];
        int validSamples = 0;
        AdaptiveUltrasoundDetector detector = new AdaptiveUltrasoundDetector(sessionConfig);

        while (running && !Thread.currentThread().isInterrupted()) {
            int read = recorder.read(readBuffer, 0, readBuffer.length);
            if (read <= 0) {
                callback.onSearching();
                continue;
            }

            int copyLength = Math.min(read, historyCapacity);
            int overflow = Math.max(0, (validSamples + copyLength) - historyCapacity);
            if (overflow > 0 && validSamples > 0) {
                System.arraycopy(history, overflow, history, 0, validSamples - overflow);
                validSamples -= overflow;
            }
            System.arraycopy(readBuffer, read - copyLength, history, validSamples, copyLength);
            validSamples += copyLength;

            if (validSamples < frameSamples) {
                callback.onSearching();
                continue;
            }

            AdaptiveUltrasoundDetector.DetectionResult best = null;
            for (int offset = 0; offset + frameSamples <= validSamples; offset += phaseStep) {
                short[] window = sliceSamples(history, offset, frameSamples);
                double noiseFloor = estimateAdaptiveNoiseFloor(window, samplesPerSymbol);
                AdaptiveUltrasoundDetector.DetectionResult result =
                        detector.detect(window, ADAPTIVE_TOKEN_BITS, noiseFloor);
                // Track best-confidence result regardless of CRC validity.
                // CRC is a helpful sanity check but not a hard gate — noisy channels
                // can corrupt the CRC bits while the token bits decode correctly.
                // Confidence (SNR + repeat agreement) is a more reliable gate here.
                if (best == null || result.getConfidence() > best.getConfidence()) {
                    best = result;
                }
                if (result.getDecodedToken() == expectedToken
                        && result.getConfidence() >= ADAPTIVE_CONFIDENCE_FLOOR) {
                    callback.onTokenDecoded(result.getDecodedToken(), result.getConfidence());
                    return;
                }
            }

            if (best != null && best.getDecodedToken() == expectedToken
                    && best.getConfidence() >= ADAPTIVE_CONFIDENCE_FLOOR) {
                callback.onTokenDecoded(best.getDecodedToken(), best.getConfidence());
                return;
            }
            callback.onSearching();
        }
    }

    /**
     * Attempts to decode a 4-bit token from the frame history.
     *
     * <p>Each OOK slot spans {@code FRAMES_PER_SLOT} analysis frames.
     * A slot is considered ON if at least half its frames detected tone.
     * Valid frame: slot[0]=ON (SYNC), slot[5]=ON (SYNC), middle 4 = data.</p>
     *
     * @return decoded token (0–15) if valid, or -1 if invalid frame.
     */
    private int tryDecodeFrame(boolean[] history, int currentIndex, int totalFrames) {
        int fallbackToken = -1;

        // The 200 ms OOK slots do not align perfectly with our ~85 ms analysis
        // frames, so a valid signal may appear shifted by one or more frames.
        // Search all phase offsets across the ring buffer and prefer the
        // expected token if any alignment yields it.
        for (int[] slotPattern : SLOT_FRAME_PATTERNS) {
            int patternFrames = sum(slotPattern);
            for (int offset = 0; offset < totalFrames; offset++) {
                int token = tryDecodeFrameAtOffset(
                        history, currentIndex, totalFrames, offset, slotPattern, patternFrames);
                if (token == expectedToken) {
                    return token;
                }
                if (fallbackToken < 0 && token >= 0) {
                    fallbackToken = token;
                }
            }
        }

        return fallbackToken;
    }

    private int tryDecodeFrameAtOffset(boolean[] history,
                                       int currentIndex,
                                       int totalFrames,
                                       int offset,
                                       int[] slotPattern,
                                       int patternFrames) {
        // Determine ON/OFF for each of the 6 OOK slots (majority vote)
        boolean[] slotValues = new boolean[OOK_FRAME_SLOTS];
        int consumedFrames = 0;
        for (int slot = 0; slot < OOK_FRAME_SLOTS; slot++) {
            int onCount = 0;
            int framesInSlot = slotPattern[slot];
            for (int f = 0; f < framesInSlot; f++) {
                int idx = ((currentIndex - patternFrames) + offset + consumedFrames + f);
                idx = ((idx % totalFrames) + totalFrames) % totalFrames;
                if (history[idx]) onCount++;
            }
            slotValues[slot] = (onCount * 2 >= framesInSlot); // majority
            consumedFrames += framesInSlot;
        }

        // Validate SYNC bits
        if (!slotValues[0] || !slotValues[5]) return -1;

        // Extract 4-bit token from data slots [1..4]
        int token = 0;
        if (slotValues[1]) token |= 8;  // bit3
        if (slotValues[2]) token |= 4;  // bit2
        if (slotValues[3]) token |= 2;  // bit1
        if (slotValues[4]) token |= 1;  // bit0

        return token;
    }

    private static int sum(int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
    }

    // ── Goertzel Algorithm ──────────────────────────────────────────────────

    /**
     * Goertzel algorithm — computes magnitude at a single DFT frequency bin.
     *
     * <p>O(N) time, 3 float variables, no arrays — the textbook choice when
     * you need energy at exactly one frequency (DTMF detection, our case).</p>
     *
     * @param samples    PCM sample buffer (16-bit signed).
     * @param numSamples number of valid samples.
     * @param targetHz   frequency to measure.
     * @return magnitude at targetHz.
     */
    static double goertzel(short[] samples, int numSamples, int targetHz) {
        double k = (double) targetHz * numSamples / SAMPLE_RATE;
        double w = 2.0 * Math.PI * k / numSamples;
        double coeff = 2.0 * Math.cos(w);

        double s0 = 0, s1 = 0, s2 = 0;

        for (int i = 0; i < numSamples; i++) {
            s0 = samples[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        return Math.sqrt(s1 * s1 + s2 * s2 - coeff * s1 * s2);
    }

    /**
     * Maps a raw Goertzel magnitude into a [0, 1] confidence score.
     *
     * <p>A logarithmic mapping works better than the old linear scale on
     * heterogeneous Android hardware: some OEM microphones reproduce the
     * same valid 18.5 kHz token with much lower raw magnitude, so a fixed
     * linear divisor caused correct detections to be treated as "skipped".</p>
     *
     * <p>Properties of this mapping:</p>
     * <ul>
     *   <li>{@link #DETECTION_THRESHOLD} maps to ~0.315, just above the DSVF
     *       room-lock minimum of 0.30.</li>
     *   <li>Stronger detections continue to increase, but the logarithmic
     *       compression prevents high-gain devices from saturating too early.</li>
     *   <li>8x the detection threshold maps to 1.0.</li>
     * </ul>
     */
    public static float normaliseConfidence(double magnitude) {
        if (magnitude <= 0.0) return 0f;

        double ratio = magnitude / DETECTION_THRESHOLD;
        double confidence = Math.log1p(Math.max(0.0, ratio))
                / Math.log1p(CONFIDENCE_MAX_RATIO);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        return (float) confidence;
    }

    private static short[] sliceSamples(short[] samples, int offset, int length) {
        short[] slice = new short[length];
        System.arraycopy(samples, offset, slice, 0, length);
        return slice;
    }

    private static double estimateAdaptiveNoiseFloor(short[] samples, int samplesPerSymbol) {
        // Use the trailing 12.5% of the buffer — tone energy rolls off after the
        // last symbol, so this region is free from the FSK carrier and gives a
        // clean noise-floor estimate. Using the first 25% contaminates the estimate
        // with the live tone and inflates adaptiveMargin, killing detection.
        int noiseWindow = Math.max(1, samplesPerSymbol / 8);
        int noiseOffset = Math.max(0, samples.length - noiseWindow);
        return Math.max(1.0, FrequencyEnergyAnalyzer.rms(samples, noiseOffset, noiseWindow));
    }
}
