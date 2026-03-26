package com.example.digitalsphere.data.ble;

/**
 * Abstraction over all BLE operations.
 * Presenters depend on this interface, not on the concrete BleManager,
 * so unit tests can inject a pure-Java FakeBleManager without an Android runtime.
 */
public interface IBleManager {

    // ── Professor mode ────────────────────────────────────────────────────

    interface ProfessorBleListener {
        void onStudentDetected(String studentName);
        void onBeaconStarted();
        void onError(String reason);
    }

    // ── Student mode ──────────────────────────────────────────────────────

    interface StudentBleListener {
        void onSignalUpdate(int rssi, boolean inRange);
        void onBeaconStarted();
        void onError(String reason);
    }

    // ── Operations ────────────────────────────────────────────────────────

    // MODIFIED — now carries barometer + session token for multi-signal verification
    void startProfessorMode(float pressureHPa, int sessionToken, ProfessorBleListener listener);
    void stopProfessorMode();

    void startStudentMode(String studentName, StudentBleListener listener);
    void stopStudentMode();
}
