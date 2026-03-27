package com.example.digitalsphere.helper;

import com.example.digitalsphere.contract.IStudentView;
import java.util.ArrayList;
import java.util.List;

/** Instrumented-test manual spy for IStudentView. */
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
    public final List<String> errors     = new ArrayList<>();
    public final List<String> statuses   = new ArrayList<>();

    @Override public void updateSignal(int rssi, boolean inRange) { lastRssi = rssi; lastInRange = inRange; }
    @Override public void onBeaconStarted()    { beaconStartedFired    = true; }
    @Override public void onAttendanceMarked() { attendanceMarkedFired = true; }
    @Override public void onAlreadyMarked()    { alreadyMarkedFired    = true; }
    @Override public void showStatus(String m) { lastStatus = m; statuses.add(m); }
    @Override public void showError(String m)  { lastError  = m; errors.add(m); }
    @Override public void setScanEnabled(boolean b)        { scanEnabled        = b; }
    @Override public void setSignalPanelVisible(boolean b) { signalPanelVisible = b; }

    public boolean lastHasBarometer;
    public boolean lastHasAudio;
    public boolean lastHasUltrasound;
    public String  lastOutcomeStatus;
    public float   lastFusionScore;
    public String  lastOutcomeReason;

    @Override public void showSensorCapabilities(boolean hasBaro, boolean hasAudio, boolean hasUltrasound) {
        lastHasBarometer = hasBaro; lastHasAudio = hasAudio; lastHasUltrasound = hasUltrasound;
    }
    @Override public void showVerificationOutcome(String status, float fusionScore, String reason) {
        lastOutcomeStatus = status; lastFusionScore = fusionScore; lastOutcomeReason = reason;
    }
}
