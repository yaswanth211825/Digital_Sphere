package com.example.digitalsphere;

import com.example.digitalsphere.data.audio.AmbientAudioRecorder;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  JVM UNIT TEST — Audio Correlator (Pearson correlation)
 * ═══════════════════════════════════════════════════════════════════════════
 *
 *  Tests {@link AmbientAudioRecorder#correlate(float[], float[])} which
 *  computes the Pearson correlation coefficient between two ambient-audio
 *  spectral hashes (float[8]).
 *
 *  Pearson is the correct metric because it is:
 *  - Gain-invariant  → different mic sensitivities don't affect the score
 *  - Offset-invariant → different baseline noise floors don't affect it
 *  - Shape-sensitive  → detects whether the energy contour matches
 *
 *  Result range: [−1.0, +1.0]
 *    +1.0 = identical shape        (same room, same moment)
 *     0.0 = uncorrelated           (different rooms, or silence)
 *    −1.0 = perfectly anti-correlated (energy pattern is inverted)
 *
 *  Same-room threshold: > 0.65 ({@link AmbientAudioRecorder#SAME_ROOM_THRESHOLD})
 *
 * ─────────────────────────────────────────────────────────────────────────
 *  TEST STRATEGY
 * ─────────────────────────────────────────────────────────────────────────
 *
 *  1. Mathematical invariants  (r = +1.0 for identical, r = −1.0 for inverted)
 *  2. Threshold behaviour      (similar → above 0.65, different → below 0.65)
 *  3. Edge cases               (all zeros, single element, null, empty)
 *  4. Real-world simulation    (classroom-realistic hash values from testing)
 *
 *  JUnit 4 only. No Mockito. No Android imports. Pure Java.
 */
public class AudioCorrelatorTest {

    /** The threshold from AmbientAudioRecorder — documented as 0.65. */
    private static final float THRESHOLD = AmbientAudioRecorder.SAME_ROOM_THRESHOLD;

    // ═════════════════════════════════════════════════════════════════════
    //  1. perfectCorrelation
    //
    //  Two identical arrays → Pearson r = +1.0 exactly.
    //
    //  This is the mathematical identity case. If this fails, the Pearson
    //  formula implementation is fundamentally broken.
    //
    //  Real-world analogue: Two phones recording the EXACT same ambient
    //  sound (e.g. if you piped the same audio into both mics via a
    //  splitter cable). The fingerprints would be identical.
    // ═════════════════════════════════════════════════════════════════════

    @Test
    public void perfectCorrelation() {
        float[] a = {0.8f, 0.6f, 0.4f, 0.3f, 0.2f, 0.15f, 0.1f, 0.05f};
        float[] b = {0.8f, 0.6f, 0.4f, 0.3f, 0.2f, 0.15f, 0.1f, 0.05f};

        float r = AmbientAudioRecorder.correlate(a, b);

        assertEquals("Identical arrays must give Pearson r = 1.0",
                1.0f, r, 0.0001f);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  2. noCorrelation
    //
    //  [1,0,1,0,1,0,1,0] vs [0,1,0,1,0,1,0,1] → perfectly anti-correlated.
    //
    //  When one array is high, the other is low, and vice versa.
    //  Pearson r should be exactly −1.0.
    //
    //  Real-world analogue: Impossible in practice (two rooms can't have
    //  perfectly inverted acoustic signatures), but this tests the
    //  mathematical correctness of the formula at its negative extreme.
    //
    //  This is a stronger condition than "no correlation" (which would be
    //  r ≈ 0.0). The arrays are perfectly ANTI-correlated, proving the
    //  implementation correctly handles negative covariance.
    // ═════════════════════════════════════════════════════════════════════

    @Test
    public void noCorrelation() {
        float[] a = {1f, 0f, 1f, 0f, 1f, 0f, 1f, 0f};
        float[] b = {0f, 1f, 0f, 1f, 0f, 1f, 0f, 1f};

        float r = AmbientAudioRecorder.correlate(a, b);

        // Should be exactly −1.0 (perfectly anti-correlated)
        assertEquals("Inverted arrays must give Pearson r = -1.0",
                -1.0f, r, 0.0001f);

        // Also verify it's well below the same-room threshold
        assertTrue("Anti-correlated signals must be below threshold. r=" + r,
                r < THRESHOLD);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  3. aboveThreshold
    //
    //  Two similar-but-not-identical arrays → r > 0.65 (same room).
    //
    //  Simulates: professor and student phones in the same classroom.
    //  Both hear the same ambient sound (HVAC, crowd murmur, projector
    //  hum) but their mic hardware and positions introduce slight
    //  differences in absolute levels.
    //
    //  The Pearson coefficient should still be high because the SHAPE
    //  of the energy contour is preserved: both phones see the same
    //  "loud band → quiet band" pattern even if absolute magnitudes differ.
    // ═════════════════════════════════════════════════════════════════════

    @Test
    public void aboveThreshold() {
        // Same shape, slight perturbation (±5–10% noise on each band)
        float[] a = {0.7f, 0.5f, 0.3f, 0.9f, 0.1f, 0.6f, 0.4f, 0.8f};
        float[] b = {0.72f, 0.48f, 0.31f, 0.88f, 0.12f, 0.58f, 0.42f, 0.78f};

        float r = AmbientAudioRecorder.correlate(a, b);

        assertTrue("Similar arrays should correlate above threshold (0.65). r=" + r,
                r > THRESHOLD);
        // Should actually be very high — near 1.0
        assertTrue("Nearly identical shapes should give r > 0.95. r=" + r,
                r > 0.95f);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  4. belowThreshold
    //
    //  Two structurally different arrays → r < 0.65 (different room).
    //
    //  Simulates: professor in a lecture hall (loud HVAC, quiet at end)
    //  vs student in a library (quiet start, loud page-turning mid-section).
    //  The temporal energy patterns are completely different shapes.
    //
    //  The Pearson coefficient should be low because the vectors have
    //  no meaningful linear relationship — peaks in one don't correspond
    //  to peaks in the other.
    // ═════════════════════════════════════════════════════════════════════

    @Test
    public void belowThreshold() {
        // Completely different energy profiles
        float[] lectureHall = {0.9f, 0.8f, 0.7f, 0.6f, 0.3f, 0.2f, 0.1f, 0.05f};
        float[] library     = {0.1f, 0.3f, 0.8f, 0.9f, 0.7f, 0.4f, 0.2f, 0.6f};

        float r = AmbientAudioRecorder.correlate(lectureHall, library);

        assertTrue("Different room profiles should correlate below threshold (0.65). r=" + r,
                r < THRESHOLD);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  5. allZeros
    //
    //  Both arrays are all zeros → r = 0.0, no exception.
    //
    //  This is the zero-variance edge case. Pearson is mathematically
    //  undefined when either vector has zero variance (denominator = 0).
    //  The implementation must return 0.0 ("uncorrelated") instead of
    //  NaN or throwing ArithmeticException.
    //
    //  Real-world analogue: Both phones are in a perfectly silent room
    //  (e.g. soundproof chamber). The RMS of every band is zero.
    //  After normalisation, the hash is [0, 0, 0, ...].
    //  Returning 0.0 is correct: silence carries no room identity
    //  information, so we can't say "same room".
    // ═════════════════════════════════════════════════════════════════════

    @Test
    public void allZeros() {
        float[] a = {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};
        float[] b = {0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f};

        float r = AmbientAudioRecorder.correlate(a, b);

        // Must return 0.0 — NOT NaN, NOT throw
        assertEquals("All-zero arrays must return 0.0 (not NaN or exception)",
                0.0f, r, 0.0001f);
        assertFalse("Result must not be NaN", Float.isNaN(r));
    }

    // ═════════════════════════════════════════════════════════════════════
    //  6. singleElement
    //
    //  Arrays of length 1 → handled gracefully (returns 0.0).
    //
    //  With a single element, Pearson is undefined: variance is zero
    //  because there's no deviation from the mean (the mean IS the
    //  single value). The implementation should return 0.0, not crash.
    //
    //  This won't happen in production (we always produce float[8]),
    //  but defensive code should handle it. A future refactoring might
    //  accidentally pass a single-element slice.
    // ═════════════════════════════════════════════════════════════════════

    @Test
    public void singleElement() {
        float[] a = {0.5f};
        float[] b = {0.8f};

        float r = AmbientAudioRecorder.correlate(a, b);

        // Single element → zero variance → denominator = 0 → return 0.0
        assertEquals("Single-element arrays should return 0.0 (undefined Pearson)",
                0.0f, r, 0.0001f);
        assertFalse("Result must not be NaN", Float.isNaN(r));
    }

    // ═════════════════════════════════════════════════════════════════════
    //  7. realWorldSameRoom
    //
    //  Realistic classroom fingerprints from two phones in the same room.
    //
    //  These values simulate a typical lecture hall recording:
    //  - Band 0 (0.00–0.25s): projector fan startup burst → high energy
    //  - Band 1 (0.25–0.50s): HVAC settling → moderate energy
    //  - Bands 2–5: steady-state ambient → decaying energy
    //  - Bands 6–7: quiet tail → low energy
    //
    //  profHash  = professor's phone (on desk, near projector)
    //  studentHash = student's phone (in hand, 5m away)
    //
    //  The student's phone sees slightly different absolute levels
    //  (distance attenuation, hand cupping the mic) but the same
    //  temporal energy SHAPE. Pearson should be > 0.65.
    // ═════════════════════════════════════════════════════════════════════

    @Test
    public void realWorldSameRoom() {
        float[] profHash    = {0.8f, 0.6f, 0.4f, 0.3f, 0.2f, 0.15f, 0.1f, 0.05f};
        float[] studentHash = {0.75f, 0.62f, 0.41f, 0.28f, 0.22f, 0.14f, 0.11f, 0.06f};

        float r = AmbientAudioRecorder.correlate(profHash, studentHash);

        assertTrue("Same-room realistic hashes should exceed threshold (0.65). r=" + r,
                r > THRESHOLD);

        // In fact, these nearly-identical shapes should be very highly correlated
        assertTrue("Nearly matching classroom fingerprints should give r > 0.99. r=" + r,
                r > 0.99f);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  8. realWorldDiffRoom
    //
    //  Realistic fingerprints from two DIFFERENT rooms.
    //
    //  profHash = lecture hall (same as test 7): energy decays monotonically
    //            from the projector-startup burst in band 0.
    //
    //  diffRoom = student lounge: energy peaks in the MIDDLE bands
    //            (band 2–3) due to a coffee machine cycling and
    //            conversation bursts, with quiet start and end.
    //
    //  The shapes are structurally different:
    //    profHash:  ████▓▓▒▒░░░░      (monotonic decay)
    //    diffRoom:  ░░██████▓▓░░      (mid-band peak)
    //
    //  Pearson should be well below 0.65.
    // ═════════════════════════════════════════════════════════════════════

    @Test
    public void realWorldDiffRoom() {
        float[] profHash = {0.8f, 0.6f, 0.4f, 0.3f, 0.2f, 0.15f, 0.1f, 0.05f};
        float[] diffRoom = {0.1f, 0.2f, 0.7f, 0.8f, 0.5f, 0.3f, 0.6f, 0.4f};

        float r = AmbientAudioRecorder.correlate(profHash, diffRoom);

        assertTrue("Different-room realistic hashes should be below threshold (0.65). r=" + r,
                r < THRESHOLD);
    }
}
