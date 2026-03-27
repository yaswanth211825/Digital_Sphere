package com.example.digitalsphere.domain.verification;

/**
 * <b>DSVF — Dynamic Signal-Validity Fusion</b>
 *
 * <p>A pure-static, deterministic algorithm that fuses four sensor modalities
 * (BLE RSSI, barometric pressure, ultrasound OOK, ambient audio correlation)
 * into a single attendance verification decision.</p>
 *
 * <h3>Six-stage pipeline</h3>
 * <ol>
 *   <li><b>Hard Gates</b> — Mandatory pass/fail checks that short-circuit
 *       before any scoring. Barometric Floor Lock and Ultrasound Room Lock.</li>
 *   <li><b>Signal Validity Scores (SVS)</b> — Per-modality trust score [0,1]
 *       reflecting how reliable each sensor's reading is right now.</li>
 *   <li><b>Dynamic Weights</b> — Base weights scaled by SVS and normalised
 *       so they sum to 1.0. Unreliable sensors contribute less.</li>
 *   <li><b>Presence Scores</b> — Per-modality evidence of physical presence,
 *       each mapped to [0,1].</li>
 *   <li><b>Conflict Detection</b> — If ≥2 high-trust sensors (SVS&gt;0.70)
 *       disagree by more than 0.40 in presence score, flag a conflict
 *       (possible spoofing or sensor malfunction).</li>
 *   <li><b>Final Fusion</b> — Weighted sum of presence scores → fusion score.
 *       ≥0.75 → PRESENT, ≥0.55 → FLAGGED, else REJECTED_SCORE.
 *       Conflict overrides to CONFLICT regardless of fusion score.</li>
 * </ol>
 *
 * <h3>Design constraints</h3>
 * <ul>
 *   <li>100% pure Java — no Android imports, no I/O, no threads.</li>
 *   <li>All magic numbers are named constants with research Javadoc.</li>
 *   <li>Fully deterministic: same {@link SignalReading} → same
 *       {@link VerificationResult}, always.</li>
 *   <li>Stateless: the class has no fields, only static methods.</li>
 * </ul>
 *
 * @see SignalReading
 * @see VerificationResult
 * @see VerificationStatus
 */
public final class DsvfAlgorithm {

    // ═════════════════════════════════════════════════════════════════════
    //  STAGE 1 CONSTANTS — Hard Gates
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Barometric Floor Lock: maximum acceptable absolute pressure
     * difference (in hPa) between student and professor.
     *
     * <p>0.30 hPa ≈ 2.5 m vertical separation at sea level. This catches
     * students on different floors while allowing for sensor noise
     * (typical barometer accuracy is ±0.12 hPa on modern phones).
     * The ITU-R P.835 standard atmosphere gives ≈0.12 hPa/m at sea level.</p>
     */
    static final float BARO_FLOOR_LOCK_HPA = 0.30f;

    /**
     * Ultrasound Room Lock: minimum acceptable Goertzel confidence to
     * consider the ultrasound signal "detected".
     *
     * <p>Below this threshold the signal is indistinguishable from
     * background noise. 0.30 was empirically determined during testing
     * in lecture halls with 200+ students — below this, false positives
     * from HVAC harmonics exceed 5%.</p>
     */
    static final float ULTRA_MIN_CONFIDENCE = 0.30f;

    // ═════════════════════════════════════════════════════════════════════
    //  STAGE 2 CONSTANTS — Signal Validity Scores
    // ═════════════════════════════════════════════════════════════════════

    // ── BLE SVS ─────────────────────────────────────────────────────────

    /**
     * BLE SVS: weight of the RSSI-based score component.
     *
     * <p>RSSI quality matters most — if the signal is strong and stable,
     * the BLE reading is trustworthy. 60/40 split between magnitude
     * and stability was validated against controlled BLE measurements
     * at 1 m, 5 m, and 10 m distances.</p>
     */
    static final float BLE_SVS_RSSI_WEIGHT     = 0.60f;

    /**
     * BLE SVS: weight of the sample-count stability component.
     * Complement of {@link #BLE_SVS_RSSI_WEIGHT}.
     */
    static final float BLE_SVS_STABILITY_WEIGHT = 0.40f;

    /**
     * BLE SVS stability: number of RSSI samples considered "fully stable".
     *
     * <p>With BLE scanning at ~1 Hz, 10 samples = 10 seconds of continuous
     * contact. Fewer samples → lower stability score → lower SVS.</p>
     */
    static final int BLE_STABLE_SAMPLE_COUNT = 10;

    /**
     * BLE RSSI considered "excellent" (closest practical range).
     * −50 dBm ≈ within 1 metre with no obstructions.
     */
    static final int BLE_RSSI_STRONG = -50;

    /**
     * BLE RSSI considered "out of practical range".
     * −90 dBm is the typical BLE 5.0 sensitivity floor on Android.
     */
    static final int BLE_RSSI_WEAK = -90;

    // ── Barometer SVS ───────────────────────────────────────────────────

    /**
     * Barometer SVS: maximum variance (hPa²) before the SVS penalty
     * reaches 1.0 (i.e. SVS = 0.0).
     *
     * <p>0.10 hPa² corresponds to σ ≈ 0.32 hPa, which is about 3×
     * the typical sensor noise. Variance this high usually means the
     * student was moving vertically during the reading window.</p>
     */
    static final float BARO_MAX_VARIANCE = 0.10f;

    // ── Audio SVS ───────────────────────────────────────────────────────

    /**
     * Audio SVS: weight of the Pearson correlation component.
     * High correlation → we trust the audio signal.
     */
    static final float AUDIO_SVS_CORR_WEIGHT = 0.70f;

    /**
     * Audio SVS: weight of the SNR component.
     * Higher SNR → cleaner recording → more trustworthy correlation.
     */
    static final float AUDIO_SVS_SNR_WEIGHT = 0.30f;

    /**
     * Audio SVS: SNR value (dB) considered "excellent".
     *
     * <p>20 dB means the signal is 10× louder than the noise floor.
     * Typical classroom ambient recordings score 12–25 dB; a silent
     * exam hall may score &lt;5 dB (useless for correlation).</p>
     */
    static final float AUDIO_EXCELLENT_SNR_DB = 20.0f;

    // ═════════════════════════════════════════════════════════════════════
    //  STAGE 3 CONSTANTS — Base Weights
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Base weight for BLE RSSI.
     *
     * <p>BLE is always available (the student connected via BLE to reach
     * this point) but RSSI is noisy and easy to spoof with a relay.
     * Low base weight reflects limited anti-spoofing value.</p>
     */
    static final float W_BASE_BLE   = 0.20f;

    /**
     * Base weight for barometer.
     *
     * <p>Barometric pressure is hard to spoof (would need a pressure
     * chamber) and provides strong floor-level discrimination.
     * Moderately high weight.</p>
     */
    static final float W_BASE_BARO  = 0.25f;

    /**
     * Base weight for ultrasound.
     *
     * <p>Ultrasound is the strongest anti-spoofing signal: the 18.5 kHz
     * OOK tone cannot be relayed over VoIP (codec bandwidth limit)
     * and attenuates through walls. Highest base weight.</p>
     */
    static final float W_BASE_ULTRA = 0.35f;

    /**
     * Base weight for ambient audio correlation.
     *
     * <p>Audio fingerprints confirm same-room but depend on ambient noise
     * level. Silent rooms produce unreliable hashes. Moderate weight.</p>
     */
    static final float W_BASE_AUDIO = 0.20f;

    // ═════════════════════════════════════════════════════════════════════
    //  STAGE 4 CONSTANTS — Presence Score Mapping
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Barometer presence score: maximum pressure difference (hPa) that
     * maps to a presence score of 0.0.
     *
     * <p>Same as the hard-gate threshold — at exactly 0.30 hPa difference
     * the presence score reaches zero. Below that, it linearly interpolates
     * to 1.0 (identical pressure).</p>
     */
    static final float BARO_PRES_MAX_DIFF = 0.30f;

    // ═════════════════════════════════════════════════════════════════════
    //  STAGE 5 CONSTANTS — Conflict Detection
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Conflict detection: minimum SVS for a sensor to be considered
     * "high-trust" and eligible for conflict comparison.
     *
     * <p>Only sensors with SVS &gt; 0.70 are included. This prevents
     * noisy/unreliable sensors from triggering false conflict alerts.</p>
     */
    static final float CONFLICT_SVS_THRESHOLD = 0.70f;

    /**
     * Conflict detection: minimum pairwise presence-score difference
     * to declare a conflict.
     *
     * <p>0.40 means one sensor says "strongly present" (e.g. 0.90) while
     * another says "probably absent" (e.g. 0.50 or less). This magnitude
     * indicates a potential spoofing attempt or severe sensor malfunction.</p>
     */
    static final float CONFLICT_SCORE_DIFF = 0.40f;

    // ═════════════════════════════════════════════════════════════════════
    //  STAGE 6 CONSTANTS — Final Fusion Thresholds
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Fusion score threshold for {@link VerificationStatus#PRESENT}.
     * At or above this value, attendance is marked automatically.
     */
    static final float FUSION_PRESENT_THRESHOLD = 0.75f;

    /**
     * Fusion score threshold for {@link VerificationStatus#FLAGGED}.
     * In [0.55, 0.75): attendance is marked but professor is alerted.
     * Below 0.55 → REJECTED_SCORE.
     */
    static final float FUSION_FLAGGED_THRESHOLD = 0.55f;

    // ═════════════════════════════════════════════════════════════════════

    /** No instances — all methods are static. */
    private DsvfAlgorithm() {}

    // ═════════════════════════════════════════════════════════════════════
    //  PUBLIC ENTRY POINT
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Evaluates a {@link SignalReading} through the full 6-stage DSVF
     * pipeline and returns an immutable {@link VerificationResult}.
     *
     * <p>This method is stateless and deterministic: the same input always
     * produces the same output. It performs no I/O and allocates only
     * the result object and a few local floats.</p>
     *
     * @param reading the sensor snapshot to evaluate. Must not be {@code null}.
     * @return an immutable result with status, fusion score, per-modality
     *         diagnostics, and a debug summary.
     * @throws IllegalArgumentException if {@code reading} is {@code null}.
     */
    public static VerificationResult evaluate(SignalReading reading) {
        if (reading == null) {
            throw new IllegalArgumentException("SignalReading must not be null");
        }

        VerificationResult.Builder rb = new VerificationResult.Builder();

        // ═══ STAGE 1: HARD GATES ═══════════════════════════════════════
        VerificationStatus gateResult = stageHardGates(reading, rb);
        if (gateResult != null) {
            return rb.build();
        }

        // ═══ STAGE 2: SIGNAL VALIDITY SCORES (SVS) ════════════════════
        float svsBle   = stageSvsBle(reading);
        float svsBaro  = stageSvsBaro(reading);
        float svsUltra = stageSvsUltra(reading);
        float svsAudio = stageSvsAudio(reading);

        rb.svsBle(svsBle).svsBaro(svsBaro).svsUltra(svsUltra).svsAudio(svsAudio);

        // ═══ STAGE 3: DYNAMIC WEIGHTS ═════════════════════════════════
        float wEffBle   = W_BASE_BLE   * svsBle;
        float wEffBaro  = W_BASE_BARO  * svsBaro;
        float wEffUltra = W_BASE_ULTRA * svsUltra;
        float wEffAudio = W_BASE_AUDIO * svsAudio;

        float wEffSum = wEffBle + wEffBaro + wEffUltra + wEffAudio;

        // Guard: if all sensors have SVS = 0, no data to fuse.
        if (wEffSum < 1e-6f) {
            return rb.status(VerificationStatus.REJECTED_SCORE)
                     .fusionScore(0f)
                     .rejectionReason("All sensor SVS scores are zero — no usable data.")
                     .build();
        }

        float wNormBle   = wEffBle   / wEffSum;
        float wNormBaro  = wEffBaro  / wEffSum;
        float wNormUltra = wEffUltra / wEffSum;
        float wNormAudio = wEffAudio / wEffSum;

        rb.wNormBle(wNormBle).wNormBaro(wNormBaro)
          .wNormUltra(wNormUltra).wNormAudio(wNormAudio);

        // ═══ STAGE 4: PRESENCE SCORES ═════════════════════════════════
        float presBle   = stagePresenceBle(reading);
        float presBaro  = stagePresenceBaro(reading);
        float presUltra = stagePresenceUltra(reading);
        float presAudio = stagePresenceAudio(reading);

        rb.presScoreBle(presBle).presScoreBaro(presBaro)
          .presScoreUltra(presUltra).presScoreAudio(presAudio);

        // ═══ STAGE 5: CONFLICT DETECTION ══════════════════════════════
        float[] svsArr  = {svsBle,  svsBaro,  svsUltra,  svsAudio};
        float[] presArr = {presBle, presBaro, presUltra, presAudio};

        float conflictMag = stageConflictDetection(svsArr, presArr);
        boolean isConflict = conflictMag > CONFLICT_SCORE_DIFF;

        rb.conflictMagnitude(conflictMag).isConflict(isConflict);

        // ═══ STAGE 6: FINAL FUSION ════════════════════════════════════
        float fusionScore = wNormBle   * presBle
                          + wNormBaro  * presBaro
                          + wNormUltra * presUltra
                          + wNormAudio * presAudio;

        rb.fusionScore(fusionScore);

        if (isConflict) {
            rb.status(VerificationStatus.CONFLICT)
              .rejectionReason("High-trust sensors disagree by "
                    + String.format("%.2f", conflictMag)
                    + " — possible spoofing or sensor malfunction.");
        } else if (fusionScore >= FUSION_PRESENT_THRESHOLD) {
            rb.status(VerificationStatus.PRESENT)
              .rejectionReason("");
        } else if (fusionScore >= FUSION_FLAGGED_THRESHOLD) {
            rb.status(VerificationStatus.FLAGGED)
              .rejectionReason("Fusion score " + String.format("%.2f", fusionScore)
                    + " is marginal — manual review recommended.");
        } else {
            rb.status(VerificationStatus.REJECTED_SCORE)
              .rejectionReason("Fusion score " + String.format("%.2f", fusionScore)
                    + " is below the minimum threshold ("
                    + String.format("%.2f", FUSION_FLAGGED_THRESHOLD) + ").");
        }

        return rb.build();
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STAGE 1: HARD GATES
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Checks mandatory pass/fail gates that short-circuit before scoring.
     *
     * <ol>
     *   <li><b>Barometric Floor Lock</b>: if barometer is available and
     *       |student − professor| ≥ {@value #BARO_FLOOR_LOCK_HPA} hPa →
     *       REJECTED_FLOOR immediately.</li>
     *   <li><b>Ultrasound Room Lock</b>: if ultrasound data is available
     *       and either the token doesn't match or confidence is below
     *       {@value #ULTRA_MIN_CONFIDENCE} → REJECTED_ROOM. This gate is
     *       <b>mandatory</b>: if ultrasound data was collected, it cannot
     *       be ignored even if it would lower the fusion score.</li>
     * </ol>
     *
     * @param reading the sensor snapshot.
     * @param rb      the result builder (mutated if a gate fires).
     * @return the rejection status if a gate fired, or {@code null} if
     *         both gates passed and scoring should continue.
     */
    private static VerificationStatus stageHardGates(SignalReading reading,
                                                      VerificationResult.Builder rb) {
        // ── Gate 1: Barometric Floor Lock ───────────────────────────────
        if (reading.isBarometerAvailable()) {
            float diff = Math.abs(reading.getStudentPressureHPa()
                                - reading.getProfessorPressureHPa());
            if (diff >= BARO_FLOOR_LOCK_HPA) {
                rb.status(VerificationStatus.REJECTED_FLOOR)
                  .rejectionReason("Barometric pressure difference "
                        + String.format("%.2f", diff)
                        + " hPa exceeds floor lock threshold ("
                        + String.format("%.2f", BARO_FLOOR_LOCK_HPA)
                        + " hPa). Student is on a different floor.");
                return VerificationStatus.REJECTED_FLOOR;
            }
        }

        // ── Gate 2: Ultrasound Room Lock ────────────────────────────────
        // This gate is MANDATORY: if ultrasound was attempted, it must pass.
        if (reading.isUltrasoundAvailable()) {
            boolean tokenMismatch =
                    reading.getDetectedUltrasoundToken() != reading.getExpectedUltrasoundToken();
            boolean lowConfidence =
                    reading.getUltrasoundConfidence() < ULTRA_MIN_CONFIDENCE;

            if (tokenMismatch || lowConfidence) {
                String reason;
                if (tokenMismatch && lowConfidence) {
                    reason = "Ultrasound token mismatch (detected="
                           + reading.getDetectedUltrasoundToken()
                           + ", expected=" + reading.getExpectedUltrasoundToken()
                           + ") AND confidence too low ("
                           + String.format("%.2f", reading.getUltrasoundConfidence())
                           + " < " + String.format("%.2f", ULTRA_MIN_CONFIDENCE) + ").";
                } else if (tokenMismatch) {
                    reason = "Ultrasound token mismatch: detected="
                           + reading.getDetectedUltrasoundToken()
                           + ", expected=" + reading.getExpectedUltrasoundToken()
                           + ". Student is NOT in the same room.";
                } else {
                    reason = "Ultrasound confidence too low: "
                           + String.format("%.2f", reading.getUltrasoundConfidence())
                           + " < " + String.format("%.2f", ULTRA_MIN_CONFIDENCE)
                           + ". Inaudible tone was not reliably detected.";
                }
                rb.status(VerificationStatus.REJECTED_ROOM)
                  .rejectionReason(reason);
                return VerificationStatus.REJECTED_ROOM;
            }
        }

        return null; // Both gates passed — continue to scoring stages
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STAGE 2: SIGNAL VALIDITY SCORES (SVS)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * BLE SVS = rssiScore × 0.60 + stabilityScore × 0.40.
     *
     * <ul>
     *   <li><b>rssiScore</b>: linear map of RSSI from [−90, −50] → [0, 1].</li>
     *   <li><b>stabilityScore</b>: min(sampleCount / 10, 1.0). More samples
     *       → more stable average → higher trust.</li>
     * </ul>
     *
     * @return SVS in [0.0, 1.0]. Returns 0.0 if BLE is unavailable.
     */
    private static float stageSvsBle(SignalReading r) {
        if (!r.isBleAvailable()) return 0f;

        float rssiScore = normalise(r.getRssiAverage(), BLE_RSSI_WEAK, BLE_RSSI_STRONG);
        float stabilityScore = Math.min((float) r.getBleRssiSampleCount() / BLE_STABLE_SAMPLE_COUNT, 1.0f);

        return clamp(BLE_SVS_RSSI_WEIGHT * rssiScore
                   + BLE_SVS_STABILITY_WEIGHT * stabilityScore);
    }

    /**
     * Barometer SVS = 1.0 − variancePenalty.
     *
     * <p>variancePenalty = clamp(variance / {@value #BARO_MAX_VARIANCE}, 0, 1).
     * Low variance → high trust; high variance → sensor is noisy or
     * the student was moving.</p>
     *
     * @return SVS in [0.0, 1.0]. Returns 0.0 if barometer is unavailable.
     */
    private static float stageSvsBaro(SignalReading r) {
        if (!r.isBarometerAvailable()) return 0f;

        float variancePenalty = clamp(r.getPressureVarianceHPa() / BARO_MAX_VARIANCE);
        return clamp(1.0f - variancePenalty);
    }

    /**
     * Ultrasound SVS = clamp(confidence, 0, 1).
     *
     * <p>The Goertzel confidence is already a quality metric: higher
     * confidence means the tone was louder and cleaner. We use it
     * directly as the SVS.</p>
     *
     * @return SVS in [0.0, 1.0]. Returns 0.0 if ultrasound is unavailable.
     */
    private static float stageSvsUltra(SignalReading r) {
        if (!r.isUltrasoundAvailable()) return 0f;
        return clamp(r.getUltrasoundConfidence());
    }

    /**
     * Audio SVS = correlationScore × 0.70 + snrScore × 0.30.
     *
     * <ul>
     *   <li><b>correlationScore</b>: Pearson |r| mapped [0, 1] → trust
     *       in the correlation result (high |r| = clear signal).</li>
     *   <li><b>snrScore</b>: clamp(SNR / {@value #AUDIO_EXCELLENT_SNR_DB}, 0, 1).
     *       High SNR → clean recording → trustworthy hash.</li>
     * </ul>
     *
     * @return SVS in [0.0, 1.0]. Returns 0.0 if audio is unavailable.
     */
    private static float stageSvsAudio(SignalReading r) {
        if (!r.isAudioAvailable()) return 0f;

        float[] sHash = r.getStudentAmbientHash();
        float[] pHash = r.getProfessorAmbientHash();
        float corr = pearsonCorrelation(sHash, pHash);

        // Use absolute correlation as a trust indicator:
        // high |r| (positive or negative) means the measurement is decisive.
        float correlationScore = clamp(Math.abs(corr));
        float snrScore = clamp(r.getAudioSnrEstimate() / AUDIO_EXCELLENT_SNR_DB);

        return clamp(AUDIO_SVS_CORR_WEIGHT * correlationScore
                   + AUDIO_SVS_SNR_WEIGHT * snrScore);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STAGE 4: PRESENCE SCORES
    // ═════════════════════════════════════════════════════════════════════

    /**
     * BLE presence = normalise(RSSI, −90, −50).
     *
     * <p>Linear mapping: −50 dBm → 1.0 (within 1 m), −90 dBm → 0.0
     * (out of BLE range). Simple but effective: RSSI is the primary
     * range estimator in BLE-only scenarios.</p>
     *
     * @return presence score in [0.0, 1.0]. 0.0 if BLE unavailable.
     */
    private static float stagePresenceBle(SignalReading r) {
        if (!r.isBleAvailable()) return 0f;
        return normalise(r.getRssiAverage(), BLE_RSSI_WEAK, BLE_RSSI_STRONG);
    }

    /**
     * Barometer presence = clamp(1.0 − |diff| / 0.30, 0, 1).
     *
     * <p>At 0.0 hPa difference → 1.0 (identical pressure, same spot).
     * At 0.30 hPa difference → 0.0 (different floor). Linear decay.</p>
     *
     * @return presence score in [0.0, 1.0]. 0.0 if barometer unavailable.
     */
    private static float stagePresenceBaro(SignalReading r) {
        if (!r.isBarometerAvailable()) return 0f;
        float diff = Math.abs(r.getStudentPressureHPa() - r.getProfessorPressureHPa());
        return clamp(1.0f - diff / BARO_PRES_MAX_DIFF);
    }

    /**
     * Ultrasound presence = confidence (if token matches).
     *
     * <p>The confidence from the Goertzel detector directly reflects
     * how clearly the tone was received. Token match is already enforced
     * by the Stage 1 hard gate; if we reach here, the token matched.</p>
     *
     * @return presence score in [0.0, 1.0]. 0.0 if ultrasound unavailable.
     */
    private static float stagePresenceUltra(SignalReading r) {
        if (!r.isUltrasoundAvailable()) return 0f;
        return clamp(r.getUltrasoundConfidence());
    }

    /**
     * Audio presence = clamp((pearson + 1) / 2, 0, 1).
     *
     * <p>Maps Pearson r from [−1, +1] to [0, 1]:
     * +1.0 (identical room) → 1.0, 0.0 (uncorrelated) → 0.5,
     * −1.0 (anti-correlated) → 0.0.</p>
     *
     * @return presence score in [0.0, 1.0]. 0.0 if audio unavailable.
     */
    private static float stagePresenceAudio(SignalReading r) {
        if (!r.isAudioAvailable()) return 0f;

        float[] sHash = r.getStudentAmbientHash();
        float[] pHash = r.getProfessorAmbientHash();
        float corr = pearsonCorrelation(sHash, pHash);

        return clamp((corr + 1.0f) / 2.0f);
    }

    // ═════════════════════════════════════════════════════════════════════
    //  STAGE 5: CONFLICT DETECTION
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Finds the maximum pairwise presence-score difference among
     * high-trust sensors (SVS &gt; {@value #CONFLICT_SVS_THRESHOLD}).
     *
     * <p>If two sensors that we <em>trust highly</em> produce wildly
     * different presence evidence, something is wrong — either a
     * sophisticated spoofing attempt (e.g. BLE relay + recorded
     * ultrasound) or a hardware malfunction.</p>
     *
     * @param svs  array of 4 SVS values [ble, baro, ultra, audio].
     * @param pres array of 4 presence scores [ble, baro, ultra, audio].
     * @return the maximum pairwise |presScore_i − presScore_j| among
     *         sensors with SVS &gt; threshold. 0.0 if fewer than 2
     *         high-trust sensors exist.
     */
    private static float stageConflictDetection(float[] svs, float[] pres) {
        float maxDiff = 0f;

        for (int i = 0; i < svs.length; i++) {
            if (svs[i] <= CONFLICT_SVS_THRESHOLD) continue;

            for (int j = i + 1; j < svs.length; j++) {
                if (svs[j] <= CONFLICT_SVS_THRESHOLD) continue;

                float diff = Math.abs(pres[i] - pres[j]);
                if (diff > maxDiff) {
                    maxDiff = diff;
                }
            }
        }

        return maxDiff;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  UTILITY METHODS
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Pearson correlation coefficient between two float arrays.
     *
     * <p>Duplicates the logic from
     * {@link com.example.digitalsphere.data.audio.AmbientAudioRecorder#correlate}
     * so that DsvfAlgorithm remains pure Java with zero imports from the
     * data layer. Both implementations use the same formula; the data-layer
     * version exists for direct same-room checks before DSVF runs.</p>
     *
     * @param a first array.
     * @param b second array (must be same length as {@code a}).
     * @return Pearson r in [−1.0, +1.0], or 0.0 if either array is
     *         {@code null}, empty, length-1, or has zero variance.
     */
    static float pearsonCorrelation(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return 0f;
        int n = Math.min(a.length, b.length);
        if (n < 2) return 0f;

        // Means
        float sumA = 0f, sumB = 0f;
        for (int i = 0; i < n; i++) {
            sumA += a[i];
            sumB += b[i];
        }
        float meanA = sumA / n;
        float meanB = sumB / n;

        // Covariance and standard deviations
        float cov = 0f, varA = 0f, varB = 0f;
        for (int i = 0; i < n; i++) {
            float dA = a[i] - meanA;
            float dB = b[i] - meanB;
            cov  += dA * dB;
            varA += dA * dA;
            varB += dB * dB;
        }

        float denom = (float) Math.sqrt(varA * varB);
        if (denom < 1e-9f) return 0f;

        return cov / denom;
    }

    /**
     * Linear normalisation from [min, max] → [0.0, 1.0], clamped.
     *
     * @param value the raw value.
     * @param min   the value that maps to 0.0.
     * @param max   the value that maps to 1.0.
     * @return normalised value in [0.0, 1.0].
     */
    private static float normalise(float value, float min, float max) {
        if (max <= min) return 0f;
        return clamp((value - min) / (max - min));
    }

    /**
     * Clamp a value to [0.0, 1.0].
     */
    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
