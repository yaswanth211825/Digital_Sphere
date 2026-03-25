package com.example.digitalsphere.contract;

import java.util.List;

/**
 * View contract for the Professor screen.
 * The presenter drives the UI exclusively through this interface,
 * keeping all business logic out of the Activity.
 */
public interface IProfessorView {

    // ── Session lifecycle ─────────────────────────────────────────────────

    /** Show the derived session ID so the professor can read it aloud (FR-07). */
    void showSessionId(String sessionId);

    /** Update the countdown timer display, e.g. "04:32". */
    void updateTimer(String timeLeft);

    /** Called when the session timer expires — auto-stops the session. */
    void onSessionExpired();

    // ── BLE / beacon ──────────────────────────────────────────────────────

    /** BLE beacon started successfully — enable the stop button. */
    void onBeaconStarted();

    /** BLE or scanning error that the user should know about. */
    void showError(String message);

    // ── Attendance list ───────────────────────────────────────────────────

    /** Replace the displayed attendance list with new data (FR-16). */
    void updateAttendanceList(List<String> entries);

    /** Update the live attendance counter badge (FR-15). */
    void updateAttendanceCount(int count);

    // ── UI state helpers ──────────────────────────────────────────────────

    /** Show or hide the progress spinner. */
    void setLoading(boolean loading);

    /** Enable / disable the Start Session button. */
    void setStartEnabled(boolean enabled);

    /** Enable / disable the Stop Session button. */
    void setStopEnabled(boolean enabled);

    /**
     * Called when the session is stopped (manually or on timer expiry).
     * B-06 fix: resets session-status label and timer display so stale text
     * from the previous session does not bleed into the next one.
     */
    void onSessionStopped();
}
