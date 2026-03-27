package com.example.digitalsphere.contract;

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
