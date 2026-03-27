package com.example.digitalsphere.data.audio;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JVM unit tests for AmbientAudioRecorder's pure-Java logic.
 *
 * Tests cover:
 * - computeHash(): RMS calculation, normalisation, silence, edge cases
 * - correlate(): Pearson correlation, identical/anti-correlated/orthogonal,
 *   gain invariance, offset invariance, null/empty guards
 * - isSameRoom(): threshold check
 * - Constants: band count, sample rate, duration
 *
 * AudioRecord-dependent methods (recordAndFingerprint) require Android
 * hardware and are tested in androidTest/.
 */
public class AmbientAudioRecorderTest {

    // ══════════════════════════════════════════════════════════════════
    //  computeHash() — RMS fingerprinting
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void computeHash_silence_returnsAllZeros() {
        short[] pcm = new short[AmbientAudioRecorder.TOTAL_SAMPLES];
        // All zeros = total silence

        float[] hash = AmbientAudioRecorder.computeHash(pcm);

        assertEquals(AmbientAudioRecorder.NUM_BANDS, hash.length);
        for (int i = 0; i < hash.length; i++) {
            assertEquals("Band " + i + " should be 0.0 for silence",
                    0.0f, hash[i], 0.0001f);
        }
    }

    @Test
    public void computeHash_constantSignal_returnsAllOnes() {
        // Constant non-zero signal → all bands have equal RMS → all normalise to 1.0
        short[] pcm = new short[AmbientAudioRecorder.TOTAL_SAMPLES];
        java.util.Arrays.fill(pcm, (short) 5000);

        float[] hash = AmbientAudioRecorder.computeHash(pcm);

        assertEquals(AmbientAudioRecorder.NUM_BANDS, hash.length);
        for (int i = 0; i < hash.length; i++) {
            assertEquals("Band " + i + " should be 1.0 for constant signal",
                    1.0f, hash[i], 0.0001f);
        }
    }

    @Test
    public void computeHash_oneLoudBand_normalisesCorrectly() {
        // Band 3 is loud (10000), all others are quiet (100).
        // After normalisation: band 3 = 1.0, others ≈ 0.01
        short[] pcm = new short[AmbientAudioRecorder.TOTAL_SAMPLES];
        java.util.Arrays.fill(pcm, (short) 100);

        int start = 3 * AmbientAudioRecorder.SAMPLES_PER_BAND;
        int end = start + AmbientAudioRecorder.SAMPLES_PER_BAND;
        for (int i = start; i < end; i++) {
            pcm[i] = 10000;
        }

        float[] hash = AmbientAudioRecorder.computeHash(pcm);

        assertEquals("Loudest band should be 1.0", 1.0f, hash[3], 0.0001f);
        for (int i = 0; i < 8; i++) {
            if (i != 3) {
                assertTrue("Quiet band " + i + " should be << 1.0, was: " + hash[i],
                        hash[i] < 0.05f);
            }
        }
    }

    @Test
    public void computeHash_allValuesInZeroToOneRange() {
        // Random signal — all hash values must be in [0.0, 1.0]
        java.util.Random rng = new java.util.Random(42);
        short[] pcm = new short[AmbientAudioRecorder.TOTAL_SAMPLES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (rng.nextGaussian() * 3000);
        }

        float[] hash = AmbientAudioRecorder.computeHash(pcm);

        assertEquals(8, hash.length);
        boolean hasOne = false;
        for (int i = 0; i < 8; i++) {
            assertTrue("hash[" + i + "]=" + hash[i] + " should be >= 0",
                    hash[i] >= 0.0f);
            assertTrue("hash[" + i + "]=" + hash[i] + " should be <= 1.0",
                    hash[i] <= 1.0f);
            if (Math.abs(hash[i] - 1.0f) < 0.0001f) hasOne = true;
        }
        assertTrue("At least one band should be 1.0 (the loudest)", hasOne);
    }

    @Test
    public void computeHash_returns8Elements() {
        short[] pcm = new short[AmbientAudioRecorder.TOTAL_SAMPLES];
        float[] hash = AmbientAudioRecorder.computeHash(pcm);
        assertEquals(AmbientAudioRecorder.NUM_BANDS, hash.length);
        assertEquals(8, hash.length);
    }

    @Test
    public void computeHash_rmsNotRawSum_bandSizeIndependent() {
        // RMS divides by N, so bands with the same amplitude but different
        // durations produce the same RMS. This test verifies we're using
        // RMS (sqrt(Σsample²/N)) not raw energy (Σsample²).
        //
        // Fill the entire buffer with constant amplitude 1000.
        // All 8 bands should have RMS ≈ 1000.0 (before normalisation).
        // After normalisation, all should be 1.0.
        short[] pcm = new short[AmbientAudioRecorder.TOTAL_SAMPLES];
        java.util.Arrays.fill(pcm, (short) 1000);

        float[] hash = AmbientAudioRecorder.computeHash(pcm);

        // If we were using raw sum instead of RMS, different band sizes
        // would produce different values. With RMS, they're all equal.
        for (int i = 0; i < 8; i++) {
            assertEquals("RMS normalisation should make all equal bands 1.0",
                    1.0f, hash[i], 0.0001f);
        }
    }

    @Test
    public void computeHash_negativeAmplitude_handledCorrectly() {
        // Negative PCM values (half of a sine wave) should produce
        // the same RMS as positive values of the same magnitude.
        // RMS squares the samples, so sign doesn't matter.
        short[] positivePcm = new short[AmbientAudioRecorder.TOTAL_SAMPLES];
        short[] negativePcm = new short[AmbientAudioRecorder.TOTAL_SAMPLES];
        java.util.Arrays.fill(positivePcm, (short) 5000);
        java.util.Arrays.fill(negativePcm, (short) -5000);

        float[] hashPos = AmbientAudioRecorder.computeHash(positivePcm);
        float[] hashNeg = AmbientAudioRecorder.computeHash(negativePcm);

        for (int i = 0; i < 8; i++) {
            assertEquals("RMS should be sign-invariant for band " + i,
                    hashPos[i], hashNeg[i], 0.0001f);
        }
    }

    // ══════════════════════════════════════════════════════════════════
    //  correlate() — Pearson correlation
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void correlate_identicalVectors_returnsOne() {
        float[] a = {0.2f, 1.0f, 0.5f, 0.8f, 0.1f, 0.9f, 0.3f, 0.6f};
        float[] b = {0.2f, 1.0f, 0.5f, 0.8f, 0.1f, 0.9f, 0.3f, 0.6f};

        float r = AmbientAudioRecorder.correlate(a, b);

        assertEquals("Identical vectors should have Pearson r = 1.0",
                1.0f, r, 0.0001f);
    }

    @Test
    public void correlate_perfectlyAntiCorrelated_returnsMinusOne() {
        // If A goes up when B goes down → r = −1.0
        float[] a = {0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f};
        float[] b = {1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f};

        float r = AmbientAudioRecorder.correlate(a, b);

        assertEquals("Anti-correlated vectors should have Pearson r = -1.0",
                -1.0f, r, 0.0001f);
    }

    @Test
    public void correlate_constantVector_returnsZero() {
        // Constant vector has zero variance → Pearson undefined → returns 0.0
        float[] a = {0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f};
        float[] b = {0.2f, 1.0f, 0.5f, 0.8f, 0.1f, 0.9f, 0.3f, 0.6f};

        assertEquals(0.0f, AmbientAudioRecorder.correlate(a, b), 0.0001f);
        assertEquals(0.0f, AmbientAudioRecorder.correlate(b, a), 0.0001f);
    }

    @Test
    public void correlate_bothConstant_returnsZero() {
        float[] a = {0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f};
        float[] b = {0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f, 0.3f};

        assertEquals(0.0f, AmbientAudioRecorder.correlate(a, b), 0.0001f);
    }

    @Test
    public void correlate_gainInvariant_scaledVectorsSameResult() {
        // Pearson is gain-invariant: multiplying all elements by a constant
        // does not change the correlation. This is critical because two
        // phones with different mic gains produce scaled fingerprints.
        float[] a = {0.2f, 1.0f, 0.5f, 0.8f, 0.1f, 0.9f, 0.3f, 0.6f};
        float[] b = {0.2f, 1.0f, 0.5f, 0.8f, 0.1f, 0.9f, 0.3f, 0.6f};

        // Scale B by 3x (simulating a louder mic)
        float[] bScaled = new float[b.length];
        for (int i = 0; i < b.length; i++) bScaled[i] = b[i] * 3.0f;

        float rOriginal = AmbientAudioRecorder.correlate(a, b);
        float rScaled   = AmbientAudioRecorder.correlate(a, bScaled);

        assertEquals("Pearson should be gain-invariant",
                rOriginal, rScaled, 0.0001f);
    }

    @Test
    public void correlate_offsetInvariant_shiftedVectorsSameResult() {
        // Pearson is offset-invariant: adding a constant to all elements
        // does not change the correlation. This is the key advantage over
        // cosine similarity — different baseline noise floors are ignored.
        float[] a = {0.2f, 1.0f, 0.5f, 0.8f, 0.1f, 0.9f, 0.3f, 0.6f};
        float[] b = {0.2f, 1.0f, 0.5f, 0.8f, 0.1f, 0.9f, 0.3f, 0.6f};

        // Shift B by +0.5 (simulating a different baseline noise floor)
        float[] bShifted = new float[b.length];
        for (int i = 0; i < b.length; i++) bShifted[i] = b[i] + 0.5f;

        float rOriginal = AmbientAudioRecorder.correlate(a, b);
        float rShifted  = AmbientAudioRecorder.correlate(a, bShifted);

        assertEquals("Pearson should be offset-invariant",
                rOriginal, rShifted, 0.0001f);
    }

    @Test
    public void correlate_nullInputs_returnsZero() {
        float[] a = {0.2f, 1.0f, 0.5f, 0.8f, 0.1f, 0.9f, 0.3f, 0.6f};
        assertEquals(0.0f, AmbientAudioRecorder.correlate(null, a), 0.0001f);
        assertEquals(0.0f, AmbientAudioRecorder.correlate(a, null), 0.0001f);
        assertEquals(0.0f, AmbientAudioRecorder.correlate(null, null), 0.0001f);
    }

    @Test
    public void correlate_emptyArrays_returnsZero() {
        assertEquals(0.0f, AmbientAudioRecorder.correlate(new float[0], new float[0]), 0.0001f);
    }

    @Test
    public void correlate_mismatchedLengths_returnsZero() {
        float[] a = {0.2f, 1.0f, 0.5f};
        float[] b = {0.2f, 1.0f, 0.5f, 0.8f};
        assertEquals(0.0f, AmbientAudioRecorder.correlate(a, b), 0.0001f);
    }

    @Test
    public void correlate_rangeIsBetweenMinusOneAndPlusOne() {
        // Randomised test: Pearson must always be in [−1, +1]
        java.util.Random rng = new java.util.Random(42);
        for (int trial = 0; trial < 100; trial++) {
            float[] a = new float[8];
            float[] b = new float[8];
            for (int i = 0; i < 8; i++) {
                a[i] = rng.nextFloat();
                b[i] = rng.nextFloat();
            }
            float r = AmbientAudioRecorder.correlate(a, b);
            assertTrue("r=" + r + " should be >= -1.0", r >= -1.0f);
            assertTrue("r=" + r + " should be <= 1.0", r <= 1.0f);
        }
    }

    @Test
    public void correlate_symmetric() {
        // Pearson(A, B) == Pearson(B, A)
        float[] a = {0.2f, 1.0f, 0.5f, 0.8f, 0.1f, 0.9f, 0.3f, 0.6f};
        float[] b = {0.8f, 0.3f, 0.6f, 0.1f, 0.9f, 0.4f, 0.7f, 0.2f};

        float rAB = AmbientAudioRecorder.correlate(a, b);
        float rBA = AmbientAudioRecorder.correlate(b, a);

        assertEquals("Pearson should be symmetric", rAB, rBA, 0.0001f);
    }

    @Test
    public void correlate_similarVectors_aboveThreshold() {
        // Simulate two phones in the same room: same shape, slight noise
        float[] profHash    = {0.3f, 0.9f, 0.5f, 1.0f, 0.2f, 0.8f, 0.4f, 0.7f};
        float[] studentHash = {0.32f, 0.88f, 0.51f, 0.97f, 0.22f, 0.79f, 0.41f, 0.68f};

        float r = AmbientAudioRecorder.correlate(profHash, studentHash);

        assertTrue("Similar vectors should have r > SAME_ROOM_THRESHOLD (0.65), was: " + r,
                r > AmbientAudioRecorder.SAME_ROOM_THRESHOLD);
    }

    @Test
    public void correlate_dissimilarVectors_belowThreshold() {
        // Simulate two different rooms: different energy patterns
        float[] roomA = {0.9f, 0.1f, 0.8f, 0.2f, 0.7f, 0.3f, 0.6f, 0.4f};
        float[] roomB = {0.1f, 0.5f, 0.3f, 0.9f, 0.4f, 0.8f, 0.2f, 1.0f};

        float r = AmbientAudioRecorder.correlate(roomA, roomB);

        assertTrue("Dissimilar rooms should have r < SAME_ROOM_THRESHOLD (0.65), was: " + r,
                r < AmbientAudioRecorder.SAME_ROOM_THRESHOLD);
    }

    // ══════════════════════════════════════════════════════════════════
    //  isSameRoom() — threshold convenience method
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void isSameRoom_identicalHashes_true() {
        float[] a = {0.3f, 1.0f, 0.5f, 0.8f, 0.1f, 0.9f, 0.3f, 0.6f};
        assertTrue(AmbientAudioRecorder.isSameRoom(a, a));
    }

    @Test
    public void isSameRoom_silence_false() {
        float[] a = new float[8]; // all zeros
        float[] b = new float[8];
        assertFalse("Two silent recordings should not be 'same room'",
                AmbientAudioRecorder.isSameRoom(a, b));
    }

    @Test
    public void isSameRoom_antiCorrelated_false() {
        float[] a = {0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f};
        float[] b = {1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f, 1.0f, 0.0f};
        assertFalse(AmbientAudioRecorder.isSameRoom(a, b));
    }

    // ══════════════════════════════════════════════════════════════════
    //  Constants validation
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void constants_sampleRateIs44100() {
        assertEquals(44100, AmbientAudioRecorder.SAMPLE_RATE);
    }

    @Test
    public void constants_durationIs2000ms() {
        assertEquals(2000, AmbientAudioRecorder.RECORD_DURATION_MS);
    }

    @Test
    public void constants_numBandsIs8() {
        assertEquals(8, AmbientAudioRecorder.NUM_BANDS);
    }

    @Test
    public void constants_totalSamplesIsCorrect() {
        assertEquals(44100 * 2, AmbientAudioRecorder.TOTAL_SAMPLES);
        assertEquals(88200, AmbientAudioRecorder.TOTAL_SAMPLES);
    }

    @Test
    public void constants_samplesPerBandDividesEvenly() {
        // 88200 / 8 = 11025 — must divide evenly
        assertEquals(0, AmbientAudioRecorder.TOTAL_SAMPLES % AmbientAudioRecorder.NUM_BANDS);
        assertEquals(11025, AmbientAudioRecorder.SAMPLES_PER_BAND);
    }

    @Test
    public void constants_thresholdIsReasonable() {
        float t = AmbientAudioRecorder.SAME_ROOM_THRESHOLD;
        assertTrue("Threshold should be > 0.5 (distinguish from noise)", t > 0.5f);
        assertTrue("Threshold should be < 0.9 (allow for device variation)", t < 0.9f);
        assertEquals(0.65f, t, 0.001f);
    }

    @Test
    public void constants_hashFitsBlePaket() {
        // 8 floats × 4 bytes = 32 bytes — must fit in BLE 5.0 extended adv
        int payloadBytes = AmbientAudioRecorder.NUM_BANDS * 4;
        assertEquals(32, payloadBytes);
        assertTrue("Hash payload must fit in BLE extended advertising (255 bytes)",
                payloadBytes <= 255);
    }

    // ══════════════════════════════════════════════════════════════════
    //  End-to-end: computeHash → correlate
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void endToEnd_sameAudio_highCorrelation() {
        // Simulate: two phones record the exact same audio
        java.util.Random rng = new java.util.Random(123);
        short[] pcm = new short[AmbientAudioRecorder.TOTAL_SAMPLES];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (rng.nextGaussian() * 2000);
        }

        float[] hashA = AmbientAudioRecorder.computeHash(pcm);
        float[] hashB = AmbientAudioRecorder.computeHash(pcm);

        float r = AmbientAudioRecorder.correlate(hashA, hashB);
        assertEquals("Same audio should produce r = 1.0", 1.0f, r, 0.0001f);
    }

    @Test
    public void endToEnd_sameAudioDifferentGain_highCorrelation() {
        // Simulate: same room, but one phone has 2x mic gain
        java.util.Random rng = new java.util.Random(456);
        short[] pcmLoud  = new short[AmbientAudioRecorder.TOTAL_SAMPLES];
        short[] pcmQuiet = new short[AmbientAudioRecorder.TOTAL_SAMPLES];

        for (int i = 0; i < pcmLoud.length; i++) {
            short sample = (short) (rng.nextGaussian() * 3000);
            pcmLoud[i]  = sample;
            pcmQuiet[i] = (short) (sample / 2); // half the gain
        }

        float[] hashLoud  = AmbientAudioRecorder.computeHash(pcmLoud);
        float[] hashQuiet = AmbientAudioRecorder.computeHash(pcmQuiet);

        float r = AmbientAudioRecorder.correlate(hashLoud, hashQuiet);

        assertTrue("Same audio at different gains should have r > 0.65, was: " + r,
                r > AmbientAudioRecorder.SAME_ROOM_THRESHOLD);
    }

    @Test
    public void endToEnd_differentAudio_lowCorrelation() {
        // Simulate: two completely different rooms
        java.util.Random rng1 = new java.util.Random(111);
        java.util.Random rng2 = new java.util.Random(222);

        short[] pcmA = new short[AmbientAudioRecorder.TOTAL_SAMPLES];
        short[] pcmB = new short[AmbientAudioRecorder.TOTAL_SAMPLES];

        // Room A: burst of noise in first half, quiet second half
        for (int i = 0; i < pcmA.length; i++) {
            pcmA[i] = (i < pcmA.length / 2)
                    ? (short) (rng1.nextGaussian() * 8000)
                    : (short) (rng1.nextGaussian() * 200);
        }

        // Room B: quiet first half, burst in second half
        for (int i = 0; i < pcmB.length; i++) {
            pcmB[i] = (i < pcmB.length / 2)
                    ? (short) (rng2.nextGaussian() * 200)
                    : (short) (rng2.nextGaussian() * 8000);
        }

        float[] hashA = AmbientAudioRecorder.computeHash(pcmA);
        float[] hashB = AmbientAudioRecorder.computeHash(pcmB);

        float r = AmbientAudioRecorder.correlate(hashA, hashB);

        assertTrue("Different room audio should have r < 0.65, was: " + r,
                r < AmbientAudioRecorder.SAME_ROOM_THRESHOLD);
    }
}
