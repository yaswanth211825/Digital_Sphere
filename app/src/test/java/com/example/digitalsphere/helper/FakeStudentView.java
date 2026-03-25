package com.example.digitalsphere.helper;

import com.example.digitalsphere.contract.IStudentView;
import java.util.ArrayList;
import java.util.List;

/**
 * Manual spy of IStudentView.
 */
public class FakeStudentView implements IStudentView {

    public int     lastRssi;
    public boolean lastInRange;
    public String  lastStatus;
    public String  lastError;

    public boolean attendanceMarkedFired = false;
    public boolean alreadyMarkedFired    = false;
    public boolean beaconStartedFired    = false;
    public boolean scanEnabled           = true;
    public boolean signalPanelVisible    = false;

    public final List<String> errors    = new ArrayList<>();
    public final List<String> statuses  = new ArrayList<>();

    // ── IStudentView ───────────────────────────────────────────────────────

    @Override public void updateSignal(int rssi, boolean inRange) {
        lastRssi    = rssi;
        lastInRange = inRange;
    }

    @Override public void onBeaconStarted()    { beaconStartedFired = true; }
    @Override public void onAttendanceMarked() { attendanceMarkedFired = true; }
    @Override public void onAlreadyMarked()    { alreadyMarkedFired = true; }

    @Override public void showStatus(String message) {
        lastStatus = message;
        statuses.add(message);
    }

    @Override public void showError(String message) {
        lastError = message;
        errors.add(message);
    }

    @Override public void setScanEnabled(boolean enabled)         { scanEnabled = enabled; }
    @Override public void setSignalPanelVisible(boolean visible)  { signalPanelVisible = visible; }
}
