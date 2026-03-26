package com.example.digitalsphere.domain.verification;

/**
 * Possible outcomes of a multi-signal attendance verification.
 *
 * The verification engine evaluates BLE RSSI, barometer delta, ultrasound
 * detection, and ambient audio correlation — then maps the aggregate
 * result onto one of these four states.
 *
 * This is a domain-level enum: pure Java, no Android imports.
 */
public enum VerificationStatus {

    /**
     * All available signals confirm the student is physically present
     * in the same room as the professor. Attendance can be marked.
     */
    VERIFIED,

    /**
     * One or more critical signals indicate the student is NOT co-located
     * with the professor. Attendance should be denied or flagged.
     */
    UNVERIFIED,

    /**
     * Verification is still in progress — not all signals have been
     * collected yet. The UI should show a "checking…" state.
     */
    PENDING,

    /**
     * A sensor or system failure prevented verification from completing.
     * This is distinct from UNVERIFIED: the student may well be present,
     * but we cannot confirm it. The professor should fall back to
     * manual verification.
     */
    ERROR
}
