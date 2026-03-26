package com.example.digitalsphere.domain.verification;

/**
 * Orchestrates all verification signals into a single pass/fail attendance
 * decision with a weighted confidence score.
 *
 * Signals evaluated (when available):
 * <ol>
 *   <li><b>BLE RSSI</b> — is the student's phone within Bluetooth range?</li>
 *   <li><b>Barometer delta</b> — are both devices on the same floor?</li>
 *   <li><b>Ultrasound detection</b> — can the student hear the professor's
 *       inaudible tone (proves line-of-sight proximity)?</li>
 *   <li><b>Audio correlation</b> — do both devices share the same ambient
 *       acoustic environment (same room)?</li>
 * </ol>
 *
 * Each signal contributes a weighted score to the aggregate confidence.
 * If a signal is unavailable (e.g. no barometer on a cheap phone) its weight
 * is redistributed to the remaining signals — the system degrades gracefully
 * instead of failing outright.
 *
 * Threshold: an aggregate confidence ≥ {@value #PASS_THRESHOLD} produces
 * {@link VerificationStatus#VERIFIED}; below that →
 * {@link VerificationStatus#UNVERIFIED} with a reason string listing which
 * signals failed.
 *
 * This class is <b>pure Java</b> — no Android imports, fully unit-testable.
 */
public class VerificationEngine {

    // ── Default weights (must sum to 1.0) ─────────────────────────────────

    /**
     * BLE is the primary signal — it's always available if the student
     * reaches this point. Heaviest weight.
     */
    private static final double W_BLE        = 0.35;

    /**
     * Ultrasound is the strongest co-location proof (cannot be relayed
     * over the internet) but requires RECORD_AUDIO permission.
     */
    private static final double W_ULTRASOUND = 0.30;

    /**
     * Audio correlation is a solid "same room" check but depends on
     * ambient noise level — silent exam halls produce low-confidence
     * fingerprints.
     */
    private static final double W_AUDIO      = 0.20;

    /**
     * Barometer confirms same-floor — useful but narrow: it only
     * rejects different-floor cheating, not same-floor-different-room.
     */
    private static final double W_BAROMETER  = 0.15;

    /** Aggregate score must reach this to pass. */
    private static final double PASS_THRESHOLD = 0.60;

    // ── Signal input holder ───────────────────────────────────────────────

    /**
     * Mutable builder that collects individual signal results as they
     * arrive from the data layer. Signals are asynchronous — BLE may
     * arrive first, barometer second, audio last — so the presenter
     * sets them incrementally and calls {@link VerificationEngine#evaluate}
     * once all available signals are in.
     */
    public static class Signals {

        /** BLE RSSI normalised to [0.0, 1.0] by the BLE layer.
         *  -50 dBm → 1.0 (excellent), -90 dBm → 0.0 (out of range). */
        public double bleScore      = -1;   // -1 = not yet collected

        /** true if the barometer readings from both devices match (same floor). */
        public boolean barometerMatch = false;

        /** true if the hardware has a barometer at all. */
        public boolean barometerAvailable = false;

        /** true if the ultrasonic tone was detected by the student's mic. */
        public boolean ultrasoundDetected = false;

        /** true if the ultrasound system was active (mic permission granted). */
        public boolean ultrasoundAvailable = false;

        /** Cosine similarity from AudioCorrelator, in [0.0, 1.0]. */
        public float audioSimilarity = -1f; // -1 = not yet collected

        /** true if ambient audio recording was attempted. */
        public boolean audioAvailable = false;
    }

    /** Private constructor — use the static {@link #evaluate} method. */
    private VerificationEngine() {}

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Evaluates all collected signals and produces a single
     * {@link VerificationResult}.
     *
     * Unavailable signals are excluded from scoring: their weight is
     * redistributed proportionally to the available signals. This means
     * a device with only BLE + ultrasound can still verify (at reduced
     * confidence) without barometer or audio correlation.
     *
     * @param signals the collected signal data. Must not be null.
     * @return an immutable {@link VerificationResult} with status,
     *         confidence, and a human-readable reason.
     */
    public static VerificationResult evaluate(Signals signals) {
        if (signals == null) {
            return VerificationResult.error("No signal data provided.");
        }

        // ── Check if BLE (mandatory signal) is present ────────────────
        if (signals.bleScore < 0) {
            return VerificationResult.pending();
        }

        // ── Calculate available weight total for redistribution ───────
        double totalWeight = W_BLE;  // BLE is always available at this point
        if (signals.ultrasoundAvailable) totalWeight += W_ULTRASOUND;
        if (signals.audioAvailable)      totalWeight += W_AUDIO;
        if (signals.barometerAvailable)  totalWeight += W_BAROMETER;

        // Avoid division by zero (shouldn't happen — BLE weight > 0).
        if (totalWeight == 0) {
            return VerificationResult.error("No verification signals available.");
        }

        // ── Score each signal ─────────────────────────────────────────
        double weightedSum = 0.0;
        StringBuilder failReasons = new StringBuilder();

        // 1. BLE RSSI
        double bleNorm = W_BLE / totalWeight;
        weightedSum += bleNorm * signals.bleScore;
        if (signals.bleScore < 0.3) {
            failReasons.append("BLE signal too weak. ");
        }

        // 2. Ultrasound
        if (signals.ultrasoundAvailable) {
            double usNorm = W_ULTRASOUND / totalWeight;
            if (signals.ultrasoundDetected) {
                weightedSum += usNorm * 1.0;
            } else {
                failReasons.append("Ultrasonic tone not detected. ");
            }
        }

        // 3. Audio correlation
        if (signals.audioAvailable) {
            double audioNorm = W_AUDIO / totalWeight;
            double audioScore = Math.max(0.0, Math.min(1.0, signals.audioSimilarity));
            weightedSum += audioNorm * audioScore;
            if (signals.audioSimilarity < 0.5) {
                failReasons.append("Ambient audio mismatch. ");
            }
        }

        // 4. Barometer
        if (signals.barometerAvailable) {
            double baroNorm = W_BAROMETER / totalWeight;
            if (signals.barometerMatch) {
                weightedSum += baroNorm * 1.0;
            } else {
                failReasons.append("Barometer mismatch: different floor detected. ");
            }
        }

        // ── Decision ──────────────────────────────────────────────────
        if (weightedSum >= PASS_THRESHOLD) {
            return VerificationResult.verified(weightedSum);
        } else {
            String reason = failReasons.length() > 0
                    ? failReasons.toString().trim()
                    : "Confidence too low.";
            return VerificationResult.unverified(weightedSum, reason);
        }
    }

    /**
     * Returns the pass threshold.
     * Exposed for unit tests and for debug/admin UI.
     */
    public static double getPassThreshold() {
        return PASS_THRESHOLD;
    }

    /**
     * Normalises a raw BLE RSSI value to a [0.0, 1.0] score.
     *
     * The mapping:
     *   -50 dBm or stronger → 1.0 (excellent — within ~1 metre)
     *   -90 dBm or weaker   → 0.0 (out of practical BLE range)
     *   Linear interpolation between -90 and -50.
     *
     * This is a utility for the presenter: convert the raw RSSI from
     * {@link com.example.digitalsphere.data.ble.BleScanner} before
     * setting it on {@link Signals#bleScore}.
     *
     * @param rssi raw RSSI in dBm (always negative).
     * @return normalised score in [0.0, 1.0].
     */
    public static double normaliseRssi(int rssi) {
        final int STRONG = -50;
        final int WEAK   = -90;
        if (rssi >= STRONG) return 1.0;
        if (rssi <= WEAK)   return 0.0;
        return (double) (rssi - WEAK) / (STRONG - WEAK);
    }
}
