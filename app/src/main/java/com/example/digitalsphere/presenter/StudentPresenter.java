package com.example.digitalsphere.presenter;

import android.content.Context;
import com.example.digitalsphere.contract.IStudentView;
import com.example.digitalsphere.data.ble.IBleManager;
import com.example.digitalsphere.data.ble.BleManager;
import com.example.digitalsphere.data.db.AttendanceRepository;
import com.example.digitalsphere.domain.IAttendanceRepository;
import com.example.digitalsphere.domain.SessionManager;
import com.example.digitalsphere.domain.model.ValidationResult;

/**
 * All business logic for the Student screen.
 *
 * Lifecycle:
 *   attach(view) → startScan(name, session) → … → stopScan() → detach()
 */
public class StudentPresenter {

    private final Context appContext;

    private IAttendanceRepository repo;
    private IBleManager           bleManager;

    private final SessionManager sessionManager = new SessionManager();
    private       IStudentView   view;

    private String  studentName;
    private String  sessionId;
    private boolean scanning      = false;
    private boolean markedPresent = false;

    // ── Constructors ───────────────────────────────────────────────────────

    /** Production constructor. */
    public StudentPresenter(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Test constructor — caller injects all dependencies. */
    public StudentPresenter(IAttendanceRepository repo, IBleManager bleManager) {
        this.appContext  = null;
        this.repo        = repo;
        this.bleManager  = bleManager;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    public void attach(IStudentView view) {
        this.view = view;
        if (repo       == null) repo       = new AttendanceRepository(appContext);
        if (bleManager == null) bleManager = new BleManager();
    }

    public void detach() {
        stopScan();
        this.view = null;
    }

    // ── Scan control ───────────────────────────────────────────────────────

    public void startScan(String rawName, String rawSession) {
        if (view == null || scanning) return;

        ValidationResult nameResult = sessionManager.validateStudentName(rawName);
        if (!nameResult.isValid()) {
            view.showError(nameResult.getMessage());
            return;
        }

        ValidationResult sessionResult = sessionManager.validateSessionInput(rawSession);
        if (!sessionResult.isValid()) {
            view.showError(sessionResult.getMessage());
            return;
        }

        studentName   = rawName.trim();
        sessionId     = sessionManager.createSessionId(rawSession);
        scanning      = true;
        markedPresent = false;

        view.setScanEnabled(false);
        view.setSignalPanelVisible(true);
        view.showStatus("Scanning for professor…");

        bleManager.startStudentMode(studentName, new IBleManager.StudentBleListener() {
            @Override public void onSignalUpdate(int rssi, boolean inRange) {
                handleSignalUpdate(rssi, inRange);
            }
            @Override public void onBeaconStarted() {
                if (view != null) {
                    // B-04 fix: re-enable the button so the user can tap "Stop Scanning".
                    // setScanEnabled(true) re-enables; onBeaconStarted() then overwrites
                    // the button text to "⏹ Stop Scanning" — both calls are needed.
                    view.setScanEnabled(true);
                    view.onBeaconStarted();
                }
            }
            @Override public void onError(String reason) {
                if (view != null) {
                    view.showError(reason);
                    view.setScanEnabled(true);
                }
                scanning = false;
            }
        });
    }

    public void stopScan() {
        scanning = false;
        bleManager.stopStudentMode();
        if (view != null) {
            view.setScanEnabled(true);
            view.setSignalPanelVisible(false);
        }
    }

    // ── Signal / attendance logic — package-private for direct testing ─────

    void handleSignalUpdate(int rssi, boolean inRange) {
        if (view == null) return;
        view.updateSignal(rssi, inRange);

        if (!inRange || markedPresent) return;

        String  deviceId      = sessionManager.buildPseudoDeviceId(studentName, sessionId);
        boolean alreadyMarked = repo.isAlreadyMarked(deviceId, sessionId);
        if (alreadyMarked) {
            markedPresent = true;
            view.onAlreadyMarked();
            return;
        }

        boolean success = repo.markPresent(studentName, deviceId, sessionId);
        if (success) {
            markedPresent = true;
            view.onAttendanceMarked();   // FR-51
        }
    }

    // ── Queries ────────────────────────────────────────────────────────────

    public boolean isScanning()      { return scanning; }
    public boolean isMarkedPresent() { return markedPresent; }
}
