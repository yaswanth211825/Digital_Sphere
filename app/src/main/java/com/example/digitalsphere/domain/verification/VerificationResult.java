package com.example.digitalsphere.domain.verification;

/**
 * Immutable value object returned by {@link DsvfAlgorithm#evaluate(SignalReading)}.
 *
 * <p>Carries the full diagnostic output of the DSVF (Dynamic Signal-Validity
 * Fusion) algorithm so that every layer — UI, logging, professor review — can
 * inspect exactly what happened and why.</p>
 *
 * <h3>Contents</h3>
 * <ul>
 *   <li><b>Status</b> — the final {@link VerificationStatus} enum.</li>
 *   <li><b>Fusion score</b> — the aggregate weighted presence score [0.0, 1.0].</li>
 *   <li><b>Per-modality SVS</b> — Signal Validity Score for BLE, barometer,
 *       ultrasound, audio.  Indicates how much we <em>trust</em> each sensor.</li>
 *   <li><b>Normalised weights</b> — the dynamic weights after SVS scaling
 *       and normalisation.  Shows how much each sensor <em>contributed</em>.</li>
 *   <li><b>Per-modality presence scores</b> — the raw 0–1 evidence that the
 *       student is physically present, per sensor.</li>
 *   <li><b>Conflict data</b> — magnitude and flag for inter-signal conflict.</li>
 *   <li><b>Rejection reason</b> — human-readable string when status is a
 *       rejection or conflict.</li>
 * </ul>
 *
 * <p>All fields are set by {@link DsvfAlgorithm} via the package-private
 * {@link Builder}. No public constructor exists — this prevents the UI layer
 * from fabricating fake results.</p>
 *
 * <p>Pure Java — no Android imports.</p>
 */
public final class VerificationResult {

    // ── Core outcome ────────────────────────────────────────────────────

    private final VerificationStatus status;
    private final float fusionScore;

    // ── Per-modality Signal Validity Scores (Stage 2) ───────────────────

    private final float svsBle;
    private final float svsBaro;
    private final float svsUltra;
    private final float svsAudio;

    // ── Normalised dynamic weights (Stage 3) ────────────────────────────

    private final float wNormBle;
    private final float wNormBaro;
    private final float wNormUltra;
    private final float wNormAudio;

    // ── Per-modality presence scores (Stage 4) ──────────────────────────

    private final float presScoreBle;
    private final float presScoreBaro;
    private final float presScoreUltra;
    private final float presScoreAudio;

    // ── Conflict detection (Stage 5) ────────────────────────────────────

    private final float conflictMagnitude;
    private final boolean isConflict;

    // ── Human-readable output ───────────────────────────────────────────

    private final String rejectionReason;

    // ── Private constructor ─────────────────────────────────────────────

    private VerificationResult(Builder b) {
        this.status             = b.status;
        this.fusionScore        = b.fusionScore;
        this.svsBle             = b.svsBle;
        this.svsBaro            = b.svsBaro;
        this.svsUltra           = b.svsUltra;
        this.svsAudio           = b.svsAudio;
        this.wNormBle           = b.wNormBle;
        this.wNormBaro          = b.wNormBaro;
        this.wNormUltra         = b.wNormUltra;
        this.wNormAudio         = b.wNormAudio;
        this.presScoreBle       = b.presScoreBle;
        this.presScoreBaro      = b.presScoreBaro;
        this.presScoreUltra     = b.presScoreUltra;
        this.presScoreAudio     = b.presScoreAudio;
        this.conflictMagnitude  = b.conflictMagnitude;
        this.isConflict         = b.isConflict;
        this.rejectionReason    = b.rejectionReason != null ? b.rejectionReason : "";
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Public getters
    // ═════════════════════════════════════════════════════════════════════

    /** The final verification outcome. */
    public VerificationStatus getStatus() { return status; }

    /**
     * Aggregate fusion score in [0.0, 1.0].
     * This is the weighted sum of per-modality presence scores.
     * ≥ 0.75 → PRESENT, ≥ 0.55 → FLAGGED, &lt; 0.55 → REJECTED_SCORE.
     */
    public float getFusionScore() { return fusionScore; }

    // ── SVS (Stage 2) ───────────────────────────────────────────────────

    /** Signal Validity Score for BLE [0.0, 1.0]. */
    public float getSvsBle()   { return svsBle; }

    /** Signal Validity Score for barometer [0.0, 1.0]. */
    public float getSvsBaro()  { return svsBaro; }

    /** Signal Validity Score for ultrasound [0.0, 1.0]. */
    public float getSvsUltra() { return svsUltra; }

    /** Signal Validity Score for audio correlation [0.0, 1.0]. */
    public float getSvsAudio() { return svsAudio; }

    // ── Normalised weights (Stage 3) ────────────────────────────────────

    /** Normalised dynamic weight for BLE (sums to 1.0 with other weights). */
    public float getWNormBle()   { return wNormBle; }

    /** Normalised dynamic weight for barometer. */
    public float getWNormBaro()  { return wNormBaro; }

    /** Normalised dynamic weight for ultrasound. */
    public float getWNormUltra() { return wNormUltra; }

    /** Normalised dynamic weight for audio. */
    public float getWNormAudio() { return wNormAudio; }

    // ── Presence scores (Stage 4) ───────────────────────────────────────

    /** Per-modality presence evidence for BLE [0.0, 1.0]. */
    public float getPresScoreBle()   { return presScoreBle; }

    /** Per-modality presence evidence for barometer [0.0, 1.0]. */
    public float getPresScoreBaro()  { return presScoreBaro; }

    /** Per-modality presence evidence for ultrasound [0.0, 1.0]. */
    public float getPresScoreUltra() { return presScoreUltra; }

    /** Per-modality presence evidence for audio [0.0, 1.0]. */
    public float getPresScoreAudio() { return presScoreAudio; }

    // ── Conflict (Stage 5) ──────────────────────────────────────────────

    /**
     * Maximum pairwise score difference between high-trust sensors
     * (those with SVS &gt; 0.70). Zero if fewer than two high-trust sensors.
     */
    public float getConflictMagnitude() { return conflictMagnitude; }

    /** {@code true} if a Stage 5 conflict was detected. */
    public boolean isConflict() { return isConflict; }

    // ── Human-readable ──────────────────────────────────────────────────

    /**
     * Human-readable reason for rejection or conflict.
     * Empty string when status is {@link VerificationStatus#PRESENT}.
     */
    public String getRejectionReason() { return rejectionReason; }

    // ═════════════════════════════════════════════════════════════════════
    //  Convenience methods
    // ═════════════════════════════════════════════════════════════════════

    /**
     * {@code true} if attendance should be marked (PRESENT or FLAGGED).
     * FLAGGED marks attendance but alerts the professor for review.
     */
    public boolean isPresent() {
        return status == VerificationStatus.PRESENT
            || status == VerificationStatus.FLAGGED;
    }

    /**
     * Multi-line debug summary showing every DSVF stage's output.
     *
     * <p>Useful for professor review screens, logging, and unit test
     * diagnostics. Example output:</p>
     * <pre>
     * ══ DSVF Result ══
     * Status       : PRESENT
     * Fusion Score : 0.82
     * ── SVS ──
     *   BLE   = 0.88   Baro  = 0.95   Ultra = 0.92   Audio = 0.73
     * ── Weights (normalised) ──
     *   BLE   = 0.18   Baro  = 0.25   Ultra = 0.35   Audio = 0.22
     * ── Presence Scores ──
     *   BLE   = 0.70   Baro  = 0.97   Ultra = 0.92   Audio = 0.81
     * ── Conflict ──
     *   Detected : false   Magnitude : 0.00
     * </pre>
     *
     * @return a non-null multi-line string with per-stage trace data.
     */
    public String debugSummary() {
        return "══ DSVF Result ══\n"
             + "Status       : " + status + "\n"
             + "Fusion Score : " + fmt(fusionScore) + "\n"
             + "── SVS ──\n"
             + "  BLE   = " + fmt(svsBle)
             + "   Baro  = " + fmt(svsBaro)
             + "   Ultra = " + fmt(svsUltra)
             + "   Audio = " + fmt(svsAudio) + "\n"
             + "── Weights (normalised) ──\n"
             + "  BLE   = " + fmt(wNormBle)
             + "   Baro  = " + fmt(wNormBaro)
             + "   Ultra = " + fmt(wNormUltra)
             + "   Audio = " + fmt(wNormAudio) + "\n"
             + "── Presence Scores ──\n"
             + "  BLE   = " + fmt(presScoreBle)
             + "   Baro  = " + fmt(presScoreBaro)
             + "   Ultra = " + fmt(presScoreUltra)
             + "   Audio = " + fmt(presScoreAudio) + "\n"
             + "── Conflict ──\n"
             + "  Detected : " + isConflict
             + "   Magnitude : " + fmt(conflictMagnitude) + "\n"
             + (rejectionReason.isEmpty() ? "" : "Reason : " + rejectionReason + "\n");
    }

    @Override
    public String toString() {
        return status + " (fusion=" + fmt(fusionScore) + ")";
    }

    private static String fmt(float v) {
        return String.format("%.2f", v);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Package-private Builder (only DsvfAlgorithm should construct these)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Mutable builder for {@link VerificationResult}.
     *
     * <p>Package-private: only {@link DsvfAlgorithm} (in the same package)
     * can create result objects. This prevents the UI layer from fabricating
     * fake verification outcomes.</p>
     */
    static final class Builder {

        VerificationStatus status = VerificationStatus.REJECTED_SCORE;
        float fusionScore;

        float svsBle;
        float svsBaro;
        float svsUltra;
        float svsAudio;

        float wNormBle;
        float wNormBaro;
        float wNormUltra;
        float wNormAudio;

        float presScoreBle;
        float presScoreBaro;
        float presScoreUltra;
        float presScoreAudio;

        float   conflictMagnitude;
        boolean isConflict;

        String rejectionReason = "";

        Builder status(VerificationStatus val)   { this.status = val;              return this; }
        Builder fusionScore(float val)           { this.fusionScore = val;         return this; }
        Builder svsBle(float val)                { this.svsBle = val;              return this; }
        Builder svsBaro(float val)               { this.svsBaro = val;             return this; }
        Builder svsUltra(float val)              { this.svsUltra = val;            return this; }
        Builder svsAudio(float val)              { this.svsAudio = val;            return this; }
        Builder wNormBle(float val)              { this.wNormBle = val;            return this; }
        Builder wNormBaro(float val)             { this.wNormBaro = val;           return this; }
        Builder wNormUltra(float val)            { this.wNormUltra = val;          return this; }
        Builder wNormAudio(float val)            { this.wNormAudio = val;          return this; }
        Builder presScoreBle(float val)          { this.presScoreBle = val;        return this; }
        Builder presScoreBaro(float val)         { this.presScoreBaro = val;       return this; }
        Builder presScoreUltra(float val)        { this.presScoreUltra = val;      return this; }
        Builder presScoreAudio(float val)        { this.presScoreAudio = val;      return this; }
        Builder conflictMagnitude(float val)     { this.conflictMagnitude = val;   return this; }
        Builder isConflict(boolean val)          { this.isConflict = val;          return this; }
        Builder rejectionReason(String val)      { this.rejectionReason = val;     return this; }

        VerificationResult build() { return new VerificationResult(this); }
    }
}
