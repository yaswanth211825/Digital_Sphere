package com.example.digitalsphere.helper;

import com.example.digitalsphere.contract.IProfessorView;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual spy of IProfessorView.
 * Records every method call so tests can assert on view state without Mockito.
 */
public class FakeProfessorView implements IProfessorView {

    // ── Captured values ───────────────────────────────────────────────────

    public String       shownSessionId;
    public String       lastError;
    public String       lastTimer;
    public List<String> lastAttendanceList  = new ArrayList<>();
    public int          lastAttendanceCount = 0;

    // ── State flags ───────────────────────────────────────────────────────

    public boolean startEnabled        = true;
    public boolean stopEnabled         = false;
    public boolean loading             = false;
    public boolean sessionExpiredFired = false;
    public boolean beaconStartedFired  = false;
    public boolean sessionStoppedFired = false;

    // ── Error history (a test may cause multiple errors) ──────────────────

    public final List<String> errors = new ArrayList<>();

    // ── IProfessorView ─────────────────────────────────────────────────────

    @Override public void showSessionId(String sessionId)           { shownSessionId = sessionId; }
    @Override public void updateTimer(String timeLeft)              { lastTimer = timeLeft; }
    @Override public void onSessionExpired()                        { sessionExpiredFired = true; }
    @Override public void onBeaconStarted()                         { beaconStartedFired = true; }
    @Override public void updateAttendanceList(List<String> entries){ lastAttendanceList = new ArrayList<>(entries); }
    @Override public void updateAttendanceCount(int count)          { lastAttendanceCount = count; }
    @Override public void setLoading(boolean loading)               { this.loading = loading; }
    @Override public void setStartEnabled(boolean enabled)          { startEnabled = enabled; }
    @Override public void setStopEnabled(boolean enabled)           { stopEnabled = enabled; }
    @Override public void onSessionStopped()                        { sessionStoppedFired = true; }

    @Override public void showError(String message) {
        lastError = message;
        errors.add(message);
    }

    // ── Sensor warning (new in IProfessorView) ──────────────────────────

    public String lastSensorWarning;

    @Override public void showSensorWarning(String message) {
        lastSensorWarning = message;
    }
}
