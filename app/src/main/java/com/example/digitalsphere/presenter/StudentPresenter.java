package com.example.digitalsphere.presenter;

import android.content.Context;
import com.example.digitalsphere.contract.IStudentView;
import com.example.digitalsphere.data.ble.IBleManager;
import com.example.digitalsphere.data.ble.BleManager;
import com.example.digitalsphere.data.db.AttendanceRepository;
import com.example.digitalsphere.domain.IAttendanceRepository;
import com.example.digitalsphere.domain.SessionManager;
import com.example.digitalsphere.domain.model.ValidationResult;
import com.example.digitalsphere.domain.verification.VerificationStatus;

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
    private boolean beaconStarting = false;
    private boolean requireUltrasound = false;
    private int     expectedUltrasoundToken = -1;
    private int     detectedUltrasoundToken = -1;
    private float   ultrasoundConfidence = 0f;

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
        startScan(rawName, rawSession, false);
    }

    public void startScan(String rawName, String rawSession, boolean requireUltrasound) {
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
        beaconStarting = false;
        this.requireUltrasound = requireUltrasound;
        this.expectedUltrasoundToken = -1;
        this.detectedUltrasoundToken = -1;
        this.ultrasoundConfidence = 0f;

        view.setScanEnabled(false);
        view.setSignalPanelVisible(true);
        view.showStatus("Scanning for professor…");

        bleManager.startStudentMode(studentName, new IBleManager.StudentBleListener() {
            @Override public void onSignalUpdate(int rssi, boolean inRange) {
                handleSignalUpdate(rssi, inRange);
            }
            @Override public void onProfessorMetadata(float pressureHPa, int sessionToken) {
                expectedUltrasoundToken = sessionToken >= 0 ? (sessionToken & 0x0F) : -1;
            }
            @Override public void onBeaconStarted() {
                if (view != null) {
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
        beaconStarting = false;
        bleManager.stopStudentMode();
        if (view != null) {
            view.setScanEnabled(true);
            view.setSignalPanelVisible(false);
        }
    }

    public void onUltrasoundDetected(int token, float confidence) {
        detectedUltrasoundToken = token;
        ultrasoundConfidence = confidence;
    }

    // ── Signal / attendance logic — package-private for direct testing ─────

    void handleSignalUpdate(int rssi, boolean inRange) {
        if (view == null) return;
        view.updateSignal(rssi, inRange);

        if (!inRange || markedPresent || beaconStarting) return;

        if (requireUltrasound) {
            if (expectedUltrasoundToken < 0) {
                view.showStatus("Found professor. Waiting for session verification…");
                return;
            }

            if (detectedUltrasoundToken < 0) {
                view.showVerificationOutcome(
                        VerificationStatus.REJECTED_ROOM.name(),
                        0f,
                        "Professor beacon found, but the ultrasound room check has not locked yet.");
                return;
            }

            if (detectedUltrasoundToken != expectedUltrasoundToken || ultrasoundConfidence < 0.30f) {
                view.showVerificationOutcome(
                        VerificationStatus.REJECTED_ROOM.name(),
                        ultrasoundConfidence,
                        "Ultrasound token mismatch or weak room signal. Move closer to the professor and retry.");
                return;
            }
        }

        String  deviceId      = sessionManager.buildPseudoDeviceId(studentName, sessionId);
        boolean alreadyMarked = repo.isAlreadyMarked(deviceId, sessionId);
        if (alreadyMarked) {
            markedPresent = true;
            view.onAlreadyMarked();
            return;
        }

        beaconStarting = true;
        bleManager.startStudentBeacon(studentName, new IBleManager.BeaconListener() {
            @Override public void onStarted() {
                beaconStarting = false;
                if (view == null) return;

                view.setScanEnabled(true);
                view.onBeaconStarted();
                if (requireUltrasound) {
                    view.showVerificationOutcome(
                            VerificationStatus.PRESENT.name(),
                            ultrasoundConfidence,
                            "");
                }

                boolean success = repo.markPresent(studentName, deviceId, sessionId);
                markedPresent = true;
                if (success) {
                    view.onAttendanceMarked();
                } else {
                    view.onAlreadyMarked();
                }
            }

            @Override public void onError(String reason) {
                beaconStarting = false;
                if (view != null) {
                    view.showError(reason);
                    view.setScanEnabled(true);
                }
            }
        });
    }

    // ── Queries ────────────────────────────────────────────────────────────

    public boolean isScanning()      { return scanning; }
    public boolean isMarkedPresent() { return markedPresent; }
}
