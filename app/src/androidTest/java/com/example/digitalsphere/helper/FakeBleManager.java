package com.example.digitalsphere.helper;

import com.example.digitalsphere.data.ble.IBleManager;

/**
 * Instrumented-test copy of the FakeBleManager.
 * Stores BLE listeners and exposes simulate*() helpers so integration
 * tests can fire BLE callbacks without real Bluetooth hardware.
 */
public class FakeBleManager implements IBleManager {

    private ProfessorBleListener professorListener;
    private StudentBleListener   studentListener;

    public boolean professorModeStarted = false;
    public boolean studentModeStarted   = false;
    public String  lastStudentName;
    public float   lastPressureHPa;       // MODIFIED
    public int     lastSessionToken;       // MODIFIED
    public float[] lastAmbientHash;

    // MODIFIED — signature now includes pressure + token
    @Override
    public void startProfessorMode(float pressureHPa, int sessionToken, float[] ambientHash, ProfessorBleListener listener) {
        this.professorListener    = listener;
        this.professorModeStarted = true;
        this.lastPressureHPa      = pressureHPa;
        this.lastSessionToken     = sessionToken;
        this.lastAmbientHash      = ambientHash;
    }

    @Override
    public void stopProfessorMode() {
        professorModeStarted = false;
        professorListener    = null;
    }

    @Override
    public void startStudentMode(String studentName, StudentBleListener listener) {
        this.studentListener    = listener;
        this.studentModeStarted = true;
        this.lastStudentName    = studentName;
    }

    @Override
    public void stopStudentMode() {
        studentModeStarted = false;
        studentListener    = null;
    }

    // ── Simulation helpers ────────────────────────────────────────────────

    public void simulateProfessorBeaconStarted()            { if (professorListener != null) professorListener.onBeaconStarted(); }
    public void simulateStudentDetected(String name)        { if (professorListener != null) professorListener.onStudentDetected(name); }
    public void simulateProfessorError(String reason)       { if (professorListener != null) professorListener.onError(reason); }
    public void simulateStudentBeaconStarted()              { if (studentListener   != null) studentListener.onBeaconStarted(); }
    public void simulateSignalUpdate(int rssi, boolean ok)  { if (studentListener   != null) studentListener.onSignalUpdate(rssi, ok); }
    public void simulateStudentError(String reason)         { if (studentListener   != null) studentListener.onError(reason); }
}
