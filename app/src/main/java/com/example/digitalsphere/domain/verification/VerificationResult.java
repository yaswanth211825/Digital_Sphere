package com.example.digitalsphere.domain.verification;

/**
 * Immutable value object returned by {@link VerificationEngine}.
 *
 * Carries three things the presenter / UI layer needs:
 *   1. {@link VerificationStatus} — the outcome enum.
 *   2. {@code confidence} — a score in [0.0, 1.0] representing how
 *      strongly the available signals support (or reject) presence.
 *      1.0 = maximum certainty, 0.0 = no supporting evidence at all.
 *   3. {@code reason} — a human-readable explanation that the UI can
 *      display to the professor or student ("All signals passed",
 *      "Barometer mismatch: different floor detected", etc.).
 *
 * Factory methods enforce valid state — there is no public constructor.
 *
 * This class is <b>pure Java</b> — no Android imports, fully unit-testable.
 * Follows the same pattern as
 * {@link com.example.digitalsphere.domain.model.ValidationResult}.
 */
public final class VerificationResult {

    private final VerificationStatus status;
    private final double             confidence;
    private final String             reason;

    // ── Private constructor — use factory methods ─────────────────────────

    private VerificationResult(VerificationStatus status, double confidence, String reason) {
        this.status     = status;
        this.confidence = clamp(confidence);
        this.reason     = (reason != null) ? reason : "";
    }

    // ── Factory methods ───────────────────────────────────────────────────

    /**
     * The student is verified as physically present.
     *
     * @param confidence weighted aggregate score from the verification engine.
     *                   Clamped to [0.0, 1.0].
     * @return an immutable result with status {@link VerificationStatus#VERIFIED}.
     */
    public static VerificationResult verified(double confidence) {
        return new VerificationResult(
                VerificationStatus.VERIFIED,
                confidence,
                "All signals confirm physical presence.");
    }

    /**
     * The student could not be verified — one or more signals failed.
     *
     * @param confidence the aggregate score (likely low).
     * @param reason     describes which signal(s) failed, e.g.
     *                   "Barometer mismatch: different floor detected."
     * @return an immutable result with status {@link VerificationStatus#UNVERIFIED}.
     */
    public static VerificationResult unverified(double confidence, String reason) {
        return new VerificationResult(VerificationStatus.UNVERIFIED, confidence, reason);
    }

    /**
     * Verification is still in progress — not all signals collected yet.
     *
     * @return an immutable result with status {@link VerificationStatus#PENDING}
     *         and zero confidence (no decision can be made yet).
     */
    public static VerificationResult pending() {
        return new VerificationResult(
                VerificationStatus.PENDING, 0.0, "Waiting for sensor data…");
    }

    /**
     * A sensor or system error prevented verification.
     *
     * @param reason describes what went wrong, e.g.
     *               "Microphone permission denied."
     * @return an immutable result with status {@link VerificationStatus#ERROR}
     *         and zero confidence.
     */
    public static VerificationResult error(String reason) {
        return new VerificationResult(VerificationStatus.ERROR, 0.0, reason);
    }

    // ── Getters ───────────────────────────────────────────────────────────

    /** The verification outcome. */
    public VerificationStatus getStatus() { return status; }

    /**
     * Aggregate confidence score in [0.0, 1.0].
     * Useful for UI (progress bar, colour coding) and for logging.
     */
    public double getConfidence() { return confidence; }

    /**
     * Human-readable explanation of the result.
     * Never null — defaults to empty string.
     */
    public String getReason() { return reason; }

    /**
     * Convenience check — equivalent to {@code getStatus() == VERIFIED}.
     */
    public boolean isVerified() {
        return status == VerificationStatus.VERIFIED;
    }

    // ── Object overrides ──────────────────────────────────────────────────

    @Override
    public String toString() {
        return status + " (confidence=" + String.format("%.2f", confidence)
                + ", reason=\"" + reason + "\")";
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /** Clamp a value to [0.0, 1.0]. */
    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
