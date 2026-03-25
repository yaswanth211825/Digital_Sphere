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
