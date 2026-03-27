package com.example.digitalsphere.contract;

import com.example.digitalsphere.domain.verification.VerificationResult; // NEW

/**
 * View contract for the Student screen.
 */
public interface IStudentView {

    // ── BLE feedback ──────────────────────────────────────────────────────

    /** Live signal-strength update (FR-22 — proximity indicator). */
    void updateSignal(int rssi, boolean inRange);

    /** Student name beacon started — show "broadcasting" status. */
    void onBeaconStarted();

    // ── Attendance ────────────────────────────────────────────────────────

    /** Attendance successfully marked — show confirmation (FR-51). */
    void onAttendanceMarked();

    /** Attendance already recorded for this session (FR-13). */
    void onAlreadyMarked();

    // ── Sensor capabilities ─────────────────────────────────────────────

    /**
     * Shows which verification sensors are available on this device.
     * Called once during initialisation so the student understands what
     * checks will run and which are skipped due to missing hardware.
     *
     * @param hasBarometer  true if TYPE_PRESSURE sensor exists.
     * @param hasAudio      true if RECORD_AUDIO permission is granted
     *                      and audio recording is possible.
     * @param hasUltrasound true if ultrasound detection is available.
     */
    void showSensorCapabilities(boolean hasBarometer, boolean hasAudio, boolean hasUltrasound);

    // ── Verification outcome ────────────────────────────────────────────

    /**
     * Displays the overall DSVF fusion result on the verification panel.
     * Called after the verification engine completes a full evaluation.
     *
     * @param status       one of: "PRESENT", "FLAGGED", "CONFLICT",
     *                     "REJECTED_FLOOR", "REJECTED_ROOM", "REJECTED_SCORE".
     * @param fusionScore  aggregate confidence in [0.0, 1.0].
     * @param reason       human-readable explanation (empty if PRESENT).
     */
    void showVerificationOutcome(String status, float fusionScore, String reason);

    // MODIFIED: New step-by-step verification hooks for the sequential student flow.

    /**
     * Shows which verification stage is currently running.
     *
     * <p>Default no-op keeps older test doubles source-compatible while the
     * new student verification flow is rolled out.</p>
     */
    default void showVerificationStep(String stepName, int stepNumber, int totalSteps) {} // NEW

    /**
     * Displays the structured verification result.
     *
     * <p>Default implementation bridges to the legacy string-based outcome
     * method so existing tests and fallback UIs still receive the result.</p>
     */
    default void showVerificationResult(VerificationResult result) { // NEW
        if (result == null) return;
        showVerificationOutcome(
                result.getStatus().name(),
                result.getFusionScore(),
                result.getRejectionReason());
    }

    /**
     * Shows a verification-specific error without forcing callers to decide
     * whether it should be surfaced as a generic error or status update.
     */
    default void showVerificationError(String error) { // NEW
        showError(error);
    }

    // ── Errors / status ───────────────────────────────────────────────────

    /** Non-fatal informational message. */
    void showStatus(String message);

    /** Blocking error the user must resolve before continuing. */
    void showError(String message);

    // ── UI state ──────────────────────────────────────────────────────────

    /** Enable / disable the Scan button. */
    void setScanEnabled(boolean enabled);

    /** Show or hide the signal strength panel. */
    void setSignalPanelVisible(boolean visible);
}
