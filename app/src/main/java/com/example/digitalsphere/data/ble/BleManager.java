package com.example.digitalsphere.data.ble;

import android.os.Handler;
import android.os.Looper;

/**
 * Concrete BLE facade. Wraps BleAdvertiser, BleScanner, StudentBeacon,
 * and ProfessorScanner into two simple modes.
 *
 * All listener callbacks are delivered on the main thread (FR-11, NFR-03).
 * Implements IBleManager so presenters depend only on the interface.
 */
public class BleManager implements IBleManager {

    // Lazily initialised so the class can be constructed in unit tests
    // (Looper.getMainLooper() is null on JVM).
    private Handler mainHandler;

    private final BleAdvertiser   professorBeacon = new BleAdvertiser();
    private final StudentBeacon   studentBeacon   = new StudentBeacon();

    private ProfessorScanner activeProfessorScanner;
    private BleScanner       activeStudentScanner;

    // ── Professor mode ────────────────────────────────────────────────────

    // MODIFIED — passes pressure + token through to BleAdvertiser
    @Override
    public void startProfessorMode(float pressureHPa, int sessionToken, float[] ambientHash, ProfessorBleListener listener) {
        professorBeacon.start(pressureHPa, sessionToken, ambientHash, new BleAdvertiser.Listener() {
            @Override public void onStarted() {
                post(listener::onBeaconStarted);
            }
            @Override public void onFailed(String reason) {
                post(() -> listener.onError(reason));
            }
        });

        activeProfessorScanner = new ProfessorScanner(new ProfessorScanner.Listener() {
            @Override public void onStudentDetected(String studentName) {
                post(() -> listener.onStudentDetected(studentName));
            }
            @Override public void onError(String reason) {
                post(() -> listener.onError(reason));
            }
        });
        activeProfessorScanner.start();
    }

    @Override
    public void stopProfessorMode() {
        professorBeacon.stop();
        if (activeProfessorScanner != null) {
            activeProfessorScanner.stop();
            activeProfessorScanner = null;
        }
    }

    // ── Student mode ──────────────────────────────────────────────────────

    @Override
    public void startStudentMode(String studentName, StudentBleListener listener) {
        activeStudentScanner = new BleScanner(new BleScanner.Listener() {
            @Override public void onResult(int rssi, boolean inRange) {
                post(() -> listener.onSignalUpdate(rssi, inRange));
            }
            @Override public void onProfessorMetadata(float pressureHPa, int sessionToken, float[] ambientHash) {
                post(() -> listener.onProfessorMetadata(pressureHPa, sessionToken, ambientHash));
            }
            @Override public void onError(String reason) {
                post(() -> listener.onError(reason));
            }
        });
        activeStudentScanner.start();
    }

    @Override
    public void startStudentBeacon(String studentName, BeaconListener listener) {
        studentBeacon.start(studentName, new StudentBeacon.Listener() {
            @Override public void onStarted() {
                post(listener::onStarted);
            }
            @Override public void onFailed(String reason) {
                post(() -> listener.onError(reason));
            }
        });
    }

    @Override
    public void stopStudentMode() {
        if (activeStudentScanner != null) {
            activeStudentScanner.stop();
            activeStudentScanner = null;
        }
        studentBeacon.stop();
    }

    // ── Internal ──────────────────────────────────────────────────────────

    private void post(Runnable r) {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        mainHandler.post(r);
    }
}
