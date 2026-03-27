package com.example.digitalsphere.domain.verification;

/**
 * Possible outcomes of the DSVF (Dynamic Signal-Validity Fusion) algorithm.
 *
 * <p>The verification engine evaluates BLE RSSI, barometer delta, ultrasound
 * detection, and ambient audio correlation through a six-stage pipeline,
 * then maps the aggregate result onto one of these six states.</p>
 *
 * <p>States are ordered by severity — {@link #PRESENT} is the only state
 * that marks attendance automatically. {@link #FLAGGED} marks attendance
 * but alerts the professor for manual review.</p>
 *
 * <p>Pure Java — no Android imports.</p>
 */
public enum VerificationStatus {

    /**
     * All signals confirm physical presence. Fusion score ≥ 0.75 with
     * no inter-signal conflict detected. Attendance is marked automatically.
     */
    PRESENT,

    /**
     * Presence is likely but not certain. Fusion score in [0.55, 0.75) OR
     * one signal is marginal. Attendance is marked but the professor is
     * notified for manual review.
     */
    FLAGGED,

    /**
     * Two or more high-trust signals (SVS &gt; 0.70) contradict each other
     * by more than 0.40 in presence score. This indicates a potential
     * spoofing attempt or severe sensor malfunction. Attendance is NOT
     * marked; the professor is alerted.
     */
    CONFLICT,

    /**
     * Hard gate failure: barometric pressure difference ≥ 0.30 hPa,
     * indicating the student is on a different floor (≥ 3 m vertical
     * separation). Short-circuits before fusion scoring.
     */
    REJECTED_FLOOR,

    /**
     * Hard gate failure: ultrasound token mismatch or confidence below
     * minimum threshold. The student's phone did not detect the professor's
     * inaudible OOK signal, proving they are NOT in the same room.
     * This gate is mandatory — ultrasound cannot be skipped.
     */
    REJECTED_ROOM,

    /**
     * Fusion score fell below the minimum threshold (0.55). No hard gate
     * failed, but the aggregate evidence is insufficient to confirm
     * presence. Attendance is NOT marked.
     */
    REJECTED_SCORE
}
