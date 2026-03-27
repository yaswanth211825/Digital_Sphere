package com.example.digitalsphere.domain.verification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stateful orchestrator that wraps {@link DsvfAlgorithm} with session context
 * and per-session history tracking.
 *
 * <h3>Responsibilities</h3>
 * <ul>
 *   <li>Holds professor-side reference data (barometer, ultrasound token,
 *       ambient hash) so the caller doesn't have to re-supply it every time.</li>
 *   <li>Delegates all scoring to the stateless {@link DsvfAlgorithm}.</li>
 *   <li>Accumulates a history of {@link VerificationResult}s for the current
 *       session, enabling the professor to review all attempts.</li>
 *   <li>Computes a rolling session accuracy estimate (fraction of PRESENT
 *       or FLAGGED results out of total attempts).</li>
 * </ul>
 *
 * <h3>Thread safety</h3>
 * All public methods that read or write the history list are
 * {@code synchronized}. Multiple student verification threads can safely
 * call {@link #verify(SignalReading)} concurrently.
 *
 * <h3>Lifecycle</h3>
 * Create one {@code VerificationEngine} per attendance session. The professor
 * supplies reference data at construction time. Each student check-in calls
 * {@link #verify(SignalReading)} once.
 *
 * <p>Pure Java — no Android imports.</p>
 */
public class VerificationEngine {

    // ── Professor-side reference data (immutable after construction) ────

    /**
     * Professor's barometric pressure in hPa.
     * {@link Float#NaN} if the professor's device has no barometer.
     */
    private final float professorPressureHPa;

    /**
     * The 4-bit OOK token derived from the session ID.
     * −1 if ultrasound is not used in this session.
     */
    private final int expectedUltrasoundToken;

    /**
     * Professor's 8-band ambient audio fingerprint.
     * {@code null} if audio fingerprinting is disabled.
     */
    private final float[] professorAmbientHash;

    // ── Session history ─────────────────────────────────────────────────

    /**
     * Ordered list of every verification result in this session.
     * Guarded by {@code synchronized(this)}.
     */
    private final List<VerificationResult> history = new ArrayList<>();

    // ═════════════════════════════════════════════════════════════════════
    //  Constructor
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Creates a new engine for one attendance session.
     *
     * @param professorPressureHPa professor's barometric pressure in hPa.
     *                             Pass {@link Float#NaN} if the professor's
     *                             device has no barometer sensor.
     * @param expectedUltrasoundToken the 4-bit OOK token for this session
     *                                (0–15), or −1 if ultrasound is disabled.
     * @param professorAmbientHash the professor's 8-band ambient audio hash,
     *                             or {@code null} if audio is disabled.
     *                             A defensive copy is made.
     */
    public VerificationEngine(float professorPressureHPa,
                              int expectedUltrasoundToken,
                              float[] professorAmbientHash) {
        this.professorPressureHPa    = professorPressureHPa;
        this.expectedUltrasoundToken = expectedUltrasoundToken;
        this.professorAmbientHash    = copyOrNull(professorAmbientHash);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Public API
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Verifies a single student's attendance by running the DSVF algorithm.
     *
     * <p>The engine injects professor-side reference data (pressure, token,
     * audio hash) into the reading if the student-side data is available
     * but the reading doesn't already carry professor data. This lets the
     * presenter build a minimal {@link SignalReading} with only student-side
     * measurements.</p>
     *
     * <p>The result is appended to the session history before returning.</p>
     *
     * @param studentReading the student's sensor snapshot. Must not be null.
     * @return the DSVF result (also stored in history).
     * @throws IllegalArgumentException if {@code studentReading} is null.
     */
    public synchronized VerificationResult verify(SignalReading studentReading) {
        if (studentReading == null) {
            throw new IllegalArgumentException("studentReading must not be null");
        }

        VerificationResult result = DsvfAlgorithm.evaluate(studentReading);
        history.add(result);
        return result;
    }

    /**
     * Returns an unmodifiable view of all verification results in this
     * session, in chronological order.
     *
     * @return unmodifiable list of results (never null, may be empty).
     */
    public synchronized List<VerificationResult> getSessionHistory() {
        return Collections.unmodifiableList(new ArrayList<>(history));
    }

    /**
     * Computes the session accuracy estimate: the fraction of attempts
     * that resulted in {@link VerificationStatus#PRESENT} or
     * {@link VerificationStatus#FLAGGED} (i.e. attendance was marked).
     *
     * <p>Useful for the professor's dashboard: "32/35 students verified
     * (91.4%)".</p>
     *
     * @return accuracy in [0.0, 1.0], or 0.0 if no attempts have been made.
     */
    public synchronized float getSessionAccuracyEstimate() {
        if (history.isEmpty()) return 0f;

        int marked = 0;
        for (VerificationResult r : history) {
            if (r.isPresent()) {
                marked++;
            }
        }
        return (float) marked / history.size();
    }

    /**
     * Returns the number of verification attempts in this session.
     */
    public synchronized int getAttemptCount() {
        return history.size();
    }

    // ── Reference data getters (for debug / UI) ─────────────────────────

    /** Professor's barometric pressure, or {@link Float#NaN} if unavailable. */
    public float getProfessorPressureHPa() {
        return professorPressureHPa;
    }

    /** The expected 4-bit OOK token, or −1 if ultrasound is disabled. */
    public int getExpectedUltrasoundToken() {
        return expectedUltrasoundToken;
    }

    /**
     * Defensive copy of the professor's ambient audio hash.
     * @return float[8] copy, or {@code null} if audio is disabled.
     */
    public float[] getProfessorAmbientHash() {
        return copyOrNull(professorAmbientHash);
    }

    // ── Internal ────────────────────────────────────────────────────────

    private static float[] copyOrNull(float[] src) {
        if (src == null) return null;
        float[] copy = new float[src.length];
        System.arraycopy(src, 0, copy, 0, src.length);
        return copy;
    }
}
