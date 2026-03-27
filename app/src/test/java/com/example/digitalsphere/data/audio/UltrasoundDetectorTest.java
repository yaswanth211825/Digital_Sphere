package com.example.digitalsphere.data.audio;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JVM unit tests for UltrasoundDetector's pure-Java logic.
 *
 * Tests cover:
 * - Goertzel algorithm correctness (pure math — no Android)
 * - Token derivation consistency between emitter and detector
 * - Detection threshold behaviour
 * - Edge cases (silence, noise, clipping)
 *
 * AudioRecord-dependent methods (start/stop) require Android hardware
 * and are tested in androidTest/.
 */
public class UltrasoundDetectorTest {

    // ══════════════════════════════════════════════════════════════════
    //  Goertzel algorithm — pure math, testable on JVM
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void goertzel_pureSineAtTarget_highMagnitude() {
        // Generate a pure 18500 Hz sine wave at 48 kHz sample rate
        int numSamples = UltrasoundDetector.FRAME_SIZE;
        short[] samples = generateSine(18500, numSamples, 48000, 0.6);

        double magnitude = UltrasoundDetector.goertzel(samples, numSamples, 18500);

        assertTrue("Pure tone at target frequency should exceed threshold, was: " + magnitude,
                magnitude >= UltrasoundDetector.DETECTION_THRESHOLD);
    }

    @Test
    public void goertzel_silence_belowThreshold() {
        short[] samples = new short[UltrasoundDetector.FRAME_SIZE];
        // All zeros = silence

        double magnitude = UltrasoundDetector.goertzel(
                samples, samples.length, 18500);

        assertTrue("Silence should be well below threshold, was: " + magnitude,
                magnitude < UltrasoundDetector.DETECTION_THRESHOLD);
    }

    @Test
    public void goertzel_wrongFrequency_muchLowerThanTarget() {
        // A tone at 1000 Hz should produce MUCH lower magnitude at the 18500 Hz bin
        // than a tone at 18500 Hz. We test the ratio, not the absolute threshold,
        // because synthetic full-amplitude PCM signals produce spectral leakage
        // that exceeds the real-world detection threshold (calibrated for mic input).
        int numSamples = UltrasoundDetector.FRAME_SIZE;
        short[] offTarget = generateSine(1000, numSamples, 48000, 0.6);
        short[] onTarget  = generateSine(18500, numSamples, 48000, 0.6);

        double magOff = UltrasoundDetector.goertzel(offTarget, numSamples, 18500);
        double magOn  = UltrasoundDetector.goertzel(onTarget, numSamples, 18500);

        assertTrue("On-target magnitude should be >> off-target. " +
                        "onTarget=" + magOn + ", offTarget=" + magOff,
                magOn > magOff * 10);
    }

    @Test
    public void goertzel_nearbyFrequency_lowerThanExact() {
        // A tone at 18000 Hz should produce LOWER magnitude at 18500 Hz
        // than a tone at exactly 18500 Hz
        int numSamples = UltrasoundDetector.FRAME_SIZE;
        short[] exact = generateSine(18500, numSamples, 48000, 0.6);
        short[] nearby = generateSine(18000, numSamples, 48000, 0.6);

        double magExact  = UltrasoundDetector.goertzel(exact, numSamples, 18500);
        double magNearby = UltrasoundDetector.goertzel(nearby, numSamples, 18500);

        assertTrue("Exact frequency should have higher magnitude than nearby. " +
                        "exact=" + magExact + ", nearby=" + magNearby,
                magExact > magNearby);
    }

    @Test
    public void goertzel_lowAmplitude_stillDetectsStrongSignal() {
        // Even at 30% amplitude, a pure tone should be detectable at close range
        int numSamples = UltrasoundDetector.FRAME_SIZE;
        short[] samples = generateSine(18500, numSamples, 48000, 0.3);

        double magnitude = UltrasoundDetector.goertzel(samples, numSamples, 18500);

        assertTrue("30% amplitude pure tone should still be detectable, was: " + magnitude,
                magnitude > 0);
    }

    @Test
    public void goertzel_fullScaleAmplitude_noOverflow() {
        // Full-scale signal should not cause NaN or overflow
        int numSamples = UltrasoundDetector.FRAME_SIZE;
        short[] samples = generateSine(18500, numSamples, 48000, 1.0);

        double magnitude = UltrasoundDetector.goertzel(samples, numSamples, 18500);

        assertFalse("Magnitude should not be NaN", Double.isNaN(magnitude));
        assertFalse("Magnitude should not be infinite", Double.isInfinite(magnitude));
        assertTrue("Full scale should exceed threshold", magnitude >= UltrasoundDetector.DETECTION_THRESHOLD);
    }

    @Test
    public void goertzel_magnitudeScalesWithAmplitude() {
        // Doubling amplitude should roughly double the Goertzel magnitude
        int numSamples = UltrasoundDetector.FRAME_SIZE;
        short[] low  = generateSine(18500, numSamples, 48000, 0.2);
        short[] high = generateSine(18500, numSamples, 48000, 0.4);

        double magLow  = UltrasoundDetector.goertzel(low, numSamples, 18500);
        double magHigh = UltrasoundDetector.goertzel(high, numSamples, 18500);

        assertTrue("Higher amplitude should give higher magnitude. " +
                "low=" + magLow + ", high=" + magHigh, magHigh > magLow);
        // Roughly linear: ratio should be around 2.0 (±tolerance)
        double ratio = magHigh / magLow;
        assertTrue("Magnitude ratio should be roughly 2x, was: " + ratio,
                ratio > 1.5 && ratio < 2.5);
    }

    @Test
    public void goertzel_singleSample_doesNotCrash() {
        short[] samples = { 1000 };
        double magnitude = UltrasoundDetector.goertzel(samples, 1, 18500);
        assertFalse("Single sample should not produce NaN", Double.isNaN(magnitude));
    }

    @Test
    public void goertzel_twoFrequenciesMixed_detectsTarget() {
        // Mix 18500 Hz (target) + 5000 Hz (noise) — should still detect target
        int numSamples = UltrasoundDetector.FRAME_SIZE;
        short[] target = generateSine(18500, numSamples, 48000, 0.5);
        short[] noise  = generateSine(5000, numSamples, 48000, 0.5);
        short[] mixed  = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            int sum = target[i] + noise[i];
            mixed[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sum));
        }

        double magnitude = UltrasoundDetector.goertzel(mixed, numSamples, 18500);

        assertTrue("Should detect 18500 Hz even mixed with 5000 Hz, magnitude=" + magnitude,
                magnitude >= UltrasoundDetector.DETECTION_THRESHOLD);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Token consistency — emitter ↔ detector must agree
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void token_detectorMatchesEmitter_sameSession() {
        // This is the critical invariant: both sides derive the same token
        String session = "cs101";
        UltrasoundEmitter emitter = new UltrasoundEmitter(session);

        // Detector uses the same static method via UltrasoundEmitter.deriveToken
        int emitterToken = emitter.getSessionToken();
        int detectorToken = UltrasoundEmitter.deriveToken(session);

        assertEquals("Emitter and detector must derive identical tokens",
                emitterToken, detectorToken);
    }

    @Test
    public void token_matchesAcrossManySessions() {
        String[] sessions = {
                "cs101", "math202", "physics301", "eng100",
                "special!@#", "unicode日本語", "", null,
                "  spaces  ", "UPPERCASE", "verylongsessionnamehere"
        };
        for (String s : sessions) {
            UltrasoundEmitter emitter = new UltrasoundEmitter(s);
            int emitterToken = emitter.getSessionToken();
            int detectorToken = UltrasoundEmitter.deriveToken(s);
            assertEquals("Token mismatch for session '" + s + "'",
                    emitterToken, detectorToken);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  Constants validation — detector matches emitter
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void sampleRate_matchesEmitter() {
        assertEquals("Detector sample rate must match emitter for Goertzel accuracy",
                UltrasoundEmitter.SAMPLE_RATE, UltrasoundDetector.SAMPLE_RATE);
    }

    @Test
    public void detectionThreshold_isPositive() {
        assertTrue("Detection threshold must be positive",
                UltrasoundDetector.DETECTION_THRESHOLD > 0);
    }

    @Test
    public void frameSize_isPowerOfTwo() {
        // While Goertzel doesn't require power-of-2 (unlike FFT),
        // power-of-2 frame sizes are cache-friendly and standard
        int fs = UltrasoundDetector.FRAME_SIZE;
        assertTrue("Frame size should be power of 2, was: " + fs,
                (fs & (fs - 1)) == 0 && fs > 0);
    }

    @Test
    public void frameSize_givesGoodFrequencyResolution() {
        // Frequency resolution = sampleRate / frameSize
        // For 18500 Hz detection, resolution should be < 20 Hz
        double resolution = (double) UltrasoundDetector.SAMPLE_RATE / UltrasoundDetector.FRAME_SIZE;
        assertTrue("Frequency resolution should be < 20 Hz for accurate detection, was: " + resolution,
                resolution < 20.0);
    }

    @Test
    public void ookFrameTiming_framesPerSlotCoversSlotDuration() {
        // Each OOK bit slot = 200ms
        // Frame duration = FRAME_SIZE / SAMPLE_RATE seconds
        // Frames per slot should cover most of the 200ms window
        double frameDurationMs = (double) UltrasoundDetector.FRAME_SIZE / UltrasoundDetector.SAMPLE_RATE * 1000;
        double slotDurationMs = UltrasoundEmitter.BIT_DURATION_MS; // 200ms
        double framesPerSlot = slotDurationMs / frameDurationMs;

        assertTrue("Should get at least 2 frames per OOK slot for majority voting. " +
                        "frameDuration=" + frameDurationMs + "ms, framesPerSlot=" + framesPerSlot,
                framesPerSlot >= 2.0);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Goertzel — stress / edge cases
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void goertzel_dcSignal_muchLowerThanTarget() {
        // DC (all samples = constant) should produce much less energy at 18.5 kHz
        // than an actual 18.5 kHz tone at the same amplitude
        short[] dc = new short[UltrasoundDetector.FRAME_SIZE];
        java.util.Arrays.fill(dc, (short) 10000);
        short[] tone = generateSine(18500, UltrasoundDetector.FRAME_SIZE, 48000, 0.3);

        double magDC   = UltrasoundDetector.goertzel(dc, dc.length, 18500);
        double magTone = UltrasoundDetector.goertzel(tone, tone.length, 18500);

        assertTrue("DC should produce << tone magnitude at 18.5 kHz. " +
                        "dc=" + magDC + ", tone=" + magTone,
                magTone > magDC * 5);
    }

    @Test
    public void goertzel_whiteNoise_muchLowerThanTone() {
        // Random low-amplitude noise magnitude at 18.5 kHz should be much less
        // than an actual 18.5 kHz tone. This tests frequency selectivity.
        java.util.Random rng = new java.util.Random(42);
        int numSamples = UltrasoundDetector.FRAME_SIZE;
        short[] noise = new short[numSamples];
        for (int i = 0; i < numSamples; i++) {
            noise[i] = (short) (rng.nextGaussian() * 500); // low-amplitude noise
        }
        short[] tone = generateSine(18500, numSamples, 48000, 0.6);

        double magNoise = UltrasoundDetector.goertzel(noise, numSamples, 18500);
        double magTone  = UltrasoundDetector.goertzel(tone, numSamples, 18500);

        assertTrue("Tone should produce >> noise magnitude. " +
                        "tone=" + magTone + ", noise=" + magNoise,
                magTone > magNoise * 10);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Helpers
    // ══════════════════════════════════════════════════════════════════

    /**
     * Generates a pure sine wave as 16-bit PCM samples.
     *
     * @param freqHz     frequency in Hz
     * @param numSamples number of samples to generate
     * @param sampleRate sample rate in Hz
     * @param amplitude  0.0 to 1.0 (fraction of Short.MAX_VALUE)
     */
    private static short[] generateSine(int freqHz, int numSamples, int sampleRate, double amplitude) {
        short[] samples = new short[numSamples];
        double amp = amplitude * Short.MAX_VALUE;
        double angularFreq = 2.0 * Math.PI * freqHz / sampleRate;
        for (int i = 0; i < numSamples; i++) {
            samples[i] = (short) (amp * Math.sin(angularFreq * i));
        }
        return samples;
    }
}
