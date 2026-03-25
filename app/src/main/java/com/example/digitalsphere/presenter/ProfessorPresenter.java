package com.example.digitalsphere.presenter;

import android.content.Context;
import android.os.CountDownTimer;
import com.example.digitalsphere.contract.IProfessorView;
import com.example.digitalsphere.data.ble.IBleManager;
import com.example.digitalsphere.data.ble.BleManager;
import com.example.digitalsphere.data.db.AttendanceRepository;
import com.example.digitalsphere.data.export.CsvExporter;
import com.example.digitalsphere.domain.IAttendanceRepository;
import com.example.digitalsphere.domain.SessionManager;
import com.example.digitalsphere.domain.model.ValidationResult;
import java.util.List;

/**
 * All business logic for the Professor screen.
 * Knows nothing about Android Views — only talks to IProfessorView.
 *
 * Lifecycle:
 *   attach(view) → startSession() → … → stopSession() → detach()
 */
public class ProfessorPresenter {

    private static final int DEFAULT_DURATION_MINUTES = 5;

    // Set by production constructor; null when using the test constructor.
    private final Context appContext;

    // Injectable for tests; lazily created from appContext in production.
    private IAttendanceRepository repo;
    private IBleManager           bleManager;

    private final SessionManager  sessionManager = new SessionManager();
    private       IProfessorView  view;

    private String         sessionId;
    private boolean        sessionActive  = false;
    private CountDownTimer countDownTimer;

    // ── Constructors ───────────────────────────────────────────────────────

    /** Production constructor — dependencies resolved from context in attach(). */
    public ProfessorPresenter(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /**
     * Test constructor — caller injects all dependencies.
     * CountDownTimer is avoided by overriding {@link #startTimer(int)}.
     */
    public ProfessorPresenter(IAttendanceRepository repo, IBleManager bleManager) {
        this.appContext  = null;
        this.repo        = repo;
        this.bleManager  = bleManager;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    public void attach(IProfessorView view) {
        this.view = view;
        if (repo       == null) repo       = new AttendanceRepository(appContext);
        if (bleManager == null) bleManager = new BleManager();
    }

    public void detach() {
        stopSession();
        this.view = null;
    }

    // ── Session control ────────────────────────────────────────────────────

    public void startSession(String rawName, String rawDuration) {
        if (view == null) return;

        ValidationResult nameResult = sessionManager.validateSessionName(rawName);
        if (!nameResult.isValid()) {
            view.showError(nameResult.getMessage());
            return;
        }

        int durationMinutes;
        try {
            durationMinutes = (rawDuration == null || rawDuration.trim().isEmpty())
                    ? DEFAULT_DURATION_MINUTES
                    : sessionManager.parseDuration(rawDuration);
        } catch (IllegalArgumentException e) {
            view.showError(e.getMessage());
            return;
        }

        sessionId     = sessionManager.createSessionId(rawName);
        sessionActive = true;

        view.showSessionId(sessionId);
        view.setStartEnabled(false);
        view.setLoading(true);

        startTimer(durationMinutes);

        bleManager.startProfessorMode(new IBleManager.ProfessorBleListener() {
            @Override public void onBeaconStarted() {
                if (view != null) {
                    view.setLoading(false);
                    view.setStopEnabled(true);
                    view.onBeaconStarted();
                }
            }
            @Override public void onStudentDetected(String studentName) {
                handleStudentDetected(studentName);
            }
            @Override public void onError(String reason) {
                if (view != null) {
                    view.setLoading(false);
                    view.showError(reason);
                    view.setStartEnabled(true);
                }
                sessionActive = false;
            }
        });
    }

    public void stopSession() {
        sessionActive = false;
        if (countDownTimer != null) {
            countDownTimer.cancel();
            countDownTimer = null;
        }
        bleManager.stopProfessorMode();
        if (view != null) {
            view.setStartEnabled(true);
            view.setStopEnabled(false);
            view.onSessionStopped();   // B-06: reset stale status label + timer
            refreshAttendance();
        }
    }

    // ── Attendance ─────────────────────────────────────────────────────────

    /** Package-private so tests can trigger it directly if needed. */
    void handleStudentDetected(String studentName) {
        if (!sessionActive || sessionId == null || view == null) return;  // FR-13 guard

        String deviceId = sessionManager.buildPseudoDeviceId(studentName, sessionId);
        // B-10 fix: only refresh when markPresent() returns true (student is new).
        // CALLBACK_TYPE_ALL_MATCHES fires on every BLE advertisement interval (~250 ms);
        // DB-level CONFLICT_IGNORE returns false for duplicates, so we skip the
        // redundant refreshAttendance() call on the repeated callbacks.
        boolean added = repo.markPresent(studentName, deviceId, sessionId);
        if (added) {
            refreshAttendance();
        }
    }

    public void refreshAttendance() {
        if (view == null || sessionId == null) return;
        List<String> list = repo.getAttendance(sessionId);
        view.updateAttendanceList(list);
        view.updateAttendanceCount(list.size());
    }

    public void exportCsv() {
        if (sessionId == null) return;
        List<String> records = repo.getAttendance(sessionId);
        CsvExporter.export(appContext, records, sessionId);
    }

    // ── Timer — protected so test subclasses can override as a no-op ───────

    protected void startTimer(int minutes) {
        long millis = (long) minutes * 60 * 1000;
        countDownTimer = new CountDownTimer(millis, 1000) {
            @Override public void onTick(long ms) {
                if (view == null) return;
                long s = ms / 1000;
                view.updateTimer(String.format("%02d:%02d", s / 60, s % 60));
            }
            @Override public void onFinish() {
                if (view != null) view.onSessionExpired();
                stopSession();
            }
        }.start();
    }

    // ── Queries ────────────────────────────────────────────────────────────

    public boolean isSessionActive() { return sessionActive; }
    public String  getSessionId()    { return sessionId; }
}
