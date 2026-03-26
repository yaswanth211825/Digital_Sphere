package com.example.digitalsphere.helper;

import com.example.digitalsphere.data.ble.IBleManager;

/**
 * Fake IBleManager for unit tests.
 * Stores the listeners and exposes simulate*() methods so test code
 * can fire BLE callbacks without any Bluetooth hardware or Android runtime.
 */
public class FakeBleManager implements IBleManager {

    // Captured listeners
    private ProfessorBleListener professorListener;
    private StudentBleListener   studentListener;

    // State tracking
    public boolean professorModeStarted = false;
    public boolean studentModeStarted   = false;
    public String  lastStudentName;
    public float   lastPressureHPa;       // MODIFIED — captures pressure arg
    public int     lastSessionToken;       // MODIFIED — captures token arg

    // ── IBleManager ────────────────────────────────────────────────────────

    // MODIFIED — signature now includes pressure + token
    @Override
    public void startProfessorMode(float pressureHPa, int sessionToken, ProfessorBleListener listener) {
        this.professorListener    = listener;
        this.professorModeStarted = true;
        this.lastPressureHPa      = pressureHPa;
        this.lastSessionToken     = sessionToken;
    }

    @Override
    public void stopProfessorMode() {
        professorModeStarted  = false;
        professorListener     = null;
    }

    @Override
    public void startStudentMode(String studentName, StudentBleListener listener) {
        this.studentListener  = listener;
        this.studentModeStarted = true;
        this.lastStudentName  = studentName;
    }

    @Override
    public void stopStudentMode() {
        studentModeStarted = false;
        studentListener    = null;
    }

    // ── Simulation helpers (called by tests) ──────────────────────────────

    public void simulateProfessorBeaconStarted() {
        if (professorListener != null) professorListener.onBeaconStarted();
    }

    public void simulateStudentDetected(String studentName) {
        if (professorListener != null) professorListener.onStudentDetected(studentName);
    }

    public void simulateProfessorError(String reason) {
        if (professorListener != null) professorListener.onError(reason);
    }

    public void simulateStudentBeaconStarted() {
        if (studentListener != null) studentListener.onBeaconStarted();
    }

    public void simulateSignalUpdate(int rssi, boolean inRange) {
        if (studentListener != null) studentListener.onSignalUpdate(rssi, inRange);
    }

    public void simulateStudentError(String reason) {
        if (studentListener != null) studentListener.onError(reason);
    }
}
