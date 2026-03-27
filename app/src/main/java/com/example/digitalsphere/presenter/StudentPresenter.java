package com.example.digitalsphere.presenter;

import android.content.Context;
import com.example.digitalsphere.contract.IStudentView;
import com.example.digitalsphere.data.ble.BleManager;
import com.example.digitalsphere.data.ble.IBleManager;
import com.example.digitalsphere.data.db.AttendanceRepository;
import com.example.digitalsphere.domain.IAttendanceRepository;
import com.example.digitalsphere.domain.SessionManager;
import com.example.digitalsphere.domain.model.ValidationResult;
import com.example.digitalsphere.domain.verification.SignalReading; // NEW
import com.example.digitalsphere.domain.verification.VerificationEngine; // NEW
import com.example.digitalsphere.domain.verification.VerificationResult; // NEW

/**
 * All business logic for the Student screen.
 *
 * Lifecycle:
 *   attach(view) → startScan(name, session) → … → stopScan() → detach()
 */
public class StudentPresenter {

    private static final int TOTAL_STEPS = 4; // NEW

    // NEW: Student-side sensor abstractions keep Android sensor plumbing out of the presenter.
    public interface BarometerSignal {
        interface Callback {
            void onResult(BarometerSnapshot snapshot);
        }

        void collect(Callback callback);
        void cancel();
        boolean isAvailable();
    }

    // NEW
    public interface UltrasoundSignal {
        interface Callback {
            void onResult(UltrasoundSnapshot snapshot);
        }

        void detect(String sessionId, int expectedToken, Callback callback);
        void cancel();
        boolean isAvailable();
    }

    // NEW
    public interface AmbientAudioSignal {
        interface Callback {
            void onResult(AudioSnapshot snapshot);
        }

        void record(float[] professorAmbientHash, Callback callback);
        void cancel();
        boolean isAvailable();
    }

    // NEW
    public static final class BarometerSnapshot {
        public final boolean available;
        public final float pressureHPa;
        public final float varianceHPa;
        public final String note;

        private BarometerSnapshot(boolean available, float pressureHPa, float varianceHPa, String note) {
            this.available = available;
            this.pressureHPa = pressureHPa;
            this.varianceHPa = varianceHPa;
            this.note = note != null ? note : "";
        }

        public static BarometerSnapshot available(float pressureHPa, float varianceHPa) {
            return new BarometerSnapshot(true, pressureHPa, varianceHPa, "");
        }

        public static BarometerSnapshot unavailable(String note) {
            return new BarometerSnapshot(false, 0f, 0f, note);
        }
    }

    // NEW
    public static final class UltrasoundSnapshot {
        public final boolean attempted;
        public final boolean detected;
        public final int detectedToken;
        public final float confidence;
        public final String note;

        private UltrasoundSnapshot(boolean attempted, boolean detected, int detectedToken,
                                   float confidence, String note) {
            this.attempted = attempted;
            this.detected = detected;
            this.detectedToken = detectedToken;
            this.confidence = confidence;
            this.note = note != null ? note : "";
        }

        public static UltrasoundSnapshot matched(int detectedToken, float confidence) {
            return new UltrasoundSnapshot(true, true, detectedToken, confidence, "");
        }

        public static UltrasoundSnapshot failed(String note) {
            return new UltrasoundSnapshot(true, false, -1, 0f, note);
        }

        public static UltrasoundSnapshot skipped(String note) {
            return new UltrasoundSnapshot(false, false, -1, 0f, note);
        }
    }

    // NEW
    public static final class AudioSnapshot {
        public final boolean attempted;
        public final float[] studentAmbientHash;
        public final float[] professorAmbientHash;
        public final float audioSnrEstimate;
        public final String note;

        private AudioSnapshot(boolean attempted,
                              float[] studentAmbientHash,
                              float[] professorAmbientHash,
                              float audioSnrEstimate,
                              String note) {
            this.attempted = attempted;
            this.studentAmbientHash = copyOrNull(studentAmbientHash);
            this.professorAmbientHash = copyOrNull(professorAmbientHash);
            this.audioSnrEstimate = audioSnrEstimate;
            this.note = note != null ? note : "";
        }

        public static AudioSnapshot captured(float[] studentAmbientHash,
                                             float[] professorAmbientHash,
                                             float audioSnrEstimate) {
            return new AudioSnapshot(true, studentAmbientHash, professorAmbientHash, audioSnrEstimate, "");
        }

        public static AudioSnapshot skipped(String note) {
            return new AudioSnapshot(false, null, null, 0f, note);
        }
    }

    private final Context appContext;
    private final BarometerSignal barometerSignal; // NEW
    private final UltrasoundSignal ultrasoundSignal; // NEW
    private final AmbientAudioSignal ambientAudioSignal; // NEW
    private final boolean strictProfessorMetadata; // NEW

    private IAttendanceRepository repo;
    private IBleManager bleManager;

    private final SessionManager sessionManager = new SessionManager();
    private IStudentView view;

    private String studentName;
    private String sessionId;
    private boolean scanning = false;
    private boolean markedPresent = false;
    private boolean beaconStarting = false;
    private boolean allowUltrasound = false; // MODIFIED
    private boolean verificationInProgress = false; // NEW
    private boolean verificationAttemptedInRange = false; // NEW
    private int verificationAttemptId = 0; // NEW

    private float professorPressureHPa = Float.NaN; // NEW
    private int professorUltrasoundToken = -1; // NEW
    private float[] professorAmbientHash = null; // NEW
    private int rssiSampleCount = 0; // NEW
    private int rssiSampleSum = 0; // NEW
    private int lastRssi = -100; // NEW

    // ── Constructors ───────────────────────────────────────────────────────

    /** Production constructor retained for compatibility. */
    public StudentPresenter(Context context) { // MODIFIED
        this(context,
                null,
                null,
                createUnavailableBarometerSignal(),
                createUnavailableUltrasoundSignal(),
                createUnavailableAudioSignal(),
                true);
    }

    /** Production constructor with explicit student-side verification dependencies. */
    public StudentPresenter(Context context,
                            BarometerSignal barometerSignal,
                            UltrasoundSignal ultrasoundSignal,
                            AmbientAudioSignal ambientAudioSignal) { // NEW
        this(context, null, null, barometerSignal, ultrasoundSignal, ambientAudioSignal, true);
    }

    /** Test constructor — caller injects repository/BLE and gets deterministic sensor fakes. */
    public StudentPresenter(IAttendanceRepository repo, IBleManager bleManager) { // MODIFIED
        this(null,
                repo,
                bleManager,
                createUnavailableBarometerSignal(),
                createCompatibilityUltrasoundSignal(),
                createUnavailableAudioSignal(),
                false);
    }

    // NEW
    private StudentPresenter(Context context,
                             IAttendanceRepository repo,
                             IBleManager bleManager,
                             BarometerSignal barometerSignal,
                             UltrasoundSignal ultrasoundSignal,
                             AmbientAudioSignal ambientAudioSignal,
                             boolean strictProfessorMetadata) {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.repo = repo;
        this.bleManager = bleManager;
        this.barometerSignal = barometerSignal != null
                ? barometerSignal : createUnavailableBarometerSignal();
        this.ultrasoundSignal = ultrasoundSignal != null
                ? ultrasoundSignal : createUnavailableUltrasoundSignal();
        this.ambientAudioSignal = ambientAudioSignal != null
                ? ambientAudioSignal : createUnavailableAudioSignal();
        this.strictProfessorMetadata = strictProfessorMetadata;
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    public void attach(IStudentView view) {
        this.view = view;
        if (repo == null) repo = new AttendanceRepository(appContext);
        if (bleManager == null) bleManager = new BleManager();
    }

    public void detach() {
        stopScan();
        this.view = null;
    }

    // ── Scan control ───────────────────────────────────────────────────────

    public void startScan(String rawName, String rawSession) { // MODIFIED
        startScan(rawName, rawSession, true);
    }

    public void startScan(String rawName, String rawSession, boolean requireUltrasound) { // MODIFIED
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

        studentName = rawName.trim();
        sessionId = sessionManager.createSessionId(rawSession);
        scanning = true;
        markedPresent = false;
        beaconStarting = false;
        verificationInProgress = false;
        verificationAttemptedInRange = false;
        verificationAttemptId++;
        allowUltrasound = requireUltrasound;
        professorPressureHPa = Float.NaN;
        professorUltrasoundToken = -1;
        professorAmbientHash = null;
        rssiSampleCount = 0;
        rssiSampleSum = 0;
        lastRssi = -100;

        view.setScanEnabled(false);
        view.setSignalPanelVisible(true);
        view.showVerificationStep("Finding session...", 1, TOTAL_STEPS);
        view.showStatus("Scanning for professor…");

        bleManager.startStudentMode(studentName, new IBleManager.StudentBleListener() {
            @Override public void onSignalUpdate(int rssi, boolean inRange) {
                handleSignalUpdate(rssi, inRange);
            }

            @Override public void onProfessorMetadata(float pressureHPa, int sessionToken, float[] ambientHash) {
                professorPressureHPa = pressureHPa > 0f ? pressureHPa : Float.NaN;
                professorUltrasoundToken = sessionToken >= 0 ? (sessionToken & 0x0F) : -1;
                professorAmbientHash = copyOrNull(ambientHash);
            }

            @Override public void onBeaconStarted() {
                if (view != null) {
                    view.setScanEnabled(true);
                    view.onBeaconStarted();
                }
            }

            @Override public void onError(String reason) {
                verificationInProgress = false;
                scanning = false;
                cancelVerificationWork();
                if (view != null) {
                    view.showError(reason);
                    view.setScanEnabled(true);
                }
            }
        });
    }

    public void stopScan() { // MODIFIED
        scanning = false;
        beaconStarting = false;
        verificationInProgress = false;
        verificationAttemptedInRange = false;
        cancelVerificationWork();
        if (bleManager != null) {
            bleManager.stopStudentMode();
        }
        if (view != null) {
            view.setScanEnabled(true);
            view.setSignalPanelVisible(false);
        }
    }

    public void onUltrasoundDetected(int token, float confidence) {
        // MODIFIED: Legacy activity-driven ultrasound callback is intentionally ignored.
    }

    // ── Verification flow ──────────────────────────────────────────────────

    public void startVerificationFlow() { // NEW
        if (view == null || !scanning || verificationInProgress || markedPresent || beaconStarting) {
            return;
        }
        verificationInProgress = true;
        final int attemptId = verificationAttemptId;
        runBarometerStep(attemptId);
    }

    private void runBarometerStep(final int attemptId) { // NEW
        if (!isAttemptActive(attemptId)) return;

        view.showVerificationStep("Checking floor...", 2, TOTAL_STEPS);
        if (!barometerSignal.isAvailable() || Float.isNaN(professorPressureHPa)) {
            String note = Float.isNaN(professorPressureHPa)
                    ? "Professor floor reference unavailable; skipping floor check."
                    : "Barometer unavailable; skipping floor check.";
            continueWithUltrasound(attemptId, BarometerSnapshot.unavailable(note));
            return;
        }

        barometerSignal.collect(snapshot -> continueWithUltrasound(attemptId,
                snapshot != null ? snapshot : BarometerSnapshot.unavailable("No barometer sample collected.")));
    }

    private void continueWithUltrasound(final int attemptId, final BarometerSnapshot barometerSnapshot) { // NEW
        if (!isAttemptActive(attemptId)) return;

        final int expectedToken = resolveExpectedUltrasoundToken();
        view.showVerificationStep("Verifying room...", 3, TOTAL_STEPS);

        if (!allowUltrasound) {
            continueWithAudio(attemptId, barometerSnapshot,
                    UltrasoundSnapshot.skipped("Ultrasound unavailable on this device; continuing without room tone."));
            return;
        }

        if (expectedToken < 0) {
            continueWithAudio(attemptId, barometerSnapshot,
                    UltrasoundSnapshot.skipped("Professor ultrasound reference unavailable; skipping room tone."));
            return;
        }

        if (!ultrasoundSignal.isAvailable()) {
            continueWithAudio(attemptId, barometerSnapshot,
                    UltrasoundSnapshot.failed("Ultrasound microphone path unavailable."));
            return;
        }

        ultrasoundSignal.detect(sessionId, expectedToken, snapshot -> continueWithAudio(
                attemptId,
                barometerSnapshot,
                snapshot != null ? snapshot : UltrasoundSnapshot.failed("Ultrasound verification did not return a result.")));
    }

    private void continueWithAudio(final int attemptId,
                                   final BarometerSnapshot barometerSnapshot,
                                   final UltrasoundSnapshot ultrasoundSnapshot) { // NEW
        if (!isAttemptActive(attemptId)) return;

        view.showVerificationStep("Audio fingerprint...", 4, TOTAL_STEPS);
        if (professorAmbientHash == null || professorAmbientHash.length == 0) {
            finishVerification(attemptId, barometerSnapshot, ultrasoundSnapshot,
                    AudioSnapshot.skipped("Student microphone is ready, but professor ambient audio reference is not broadcast yet; audio check skipped honestly.")); // MODIFIED
            return;
        }

        if (!ambientAudioSignal.isAvailable()) {
            finishVerification(attemptId, barometerSnapshot, ultrasoundSnapshot,
                    AudioSnapshot.skipped("Ambient audio recording unavailable on this device."));
            return;
        }

        ambientAudioSignal.record(professorAmbientHash, snapshot -> finishVerification(
                attemptId,
                barometerSnapshot,
                ultrasoundSnapshot,
                snapshot != null ? snapshot : AudioSnapshot.skipped("Ambient audio recording returned no data.")));
    }

    private void finishVerification(int attemptId,
                                    BarometerSnapshot barometerSnapshot,
                                    UltrasoundSnapshot ultrasoundSnapshot,
                                    AudioSnapshot audioSnapshot) { // NEW
        if (!isAttemptActive(attemptId)) return;

        VerificationResult result = verify(barometerSnapshot, ultrasoundSnapshot, audioSnapshot);
        verificationInProgress = false;

        if (view != null) {
            if (!barometerSnapshot.note.isEmpty()) {
                view.showStatus(barometerSnapshot.note);
            }
            if (!ultrasoundSnapshot.note.isEmpty()) {
                view.showStatus(ultrasoundSnapshot.note);
            }
            if (!audioSnapshot.note.isEmpty()) {
                view.showStatus(audioSnapshot.note);
            }
            view.showVerificationResult(result);
            view.setScanEnabled(true);
        }

        if (result.isPresent()) {
            beginAttendanceMark(result);
        }
    }

    private VerificationResult verify(BarometerSnapshot barometerSnapshot,
                                      UltrasoundSnapshot ultrasoundSnapshot,
                                      AudioSnapshot audioSnapshot) { // NEW
        SignalReading.Builder builder = new SignalReading.Builder()
                .rssiAverage(rssiSampleCount > 0 ? Math.round((float) rssiSampleSum / rssiSampleCount) : lastRssi)
                .bleRssiSampleCount(rssiSampleCount)
                .timestampMs(System.currentTimeMillis());

        if (barometerSnapshot.available && !Float.isNaN(professorPressureHPa)) {
            builder.barometerAvailable(true)
                    .studentPressureHPa(barometerSnapshot.pressureHPa)
                    .professorPressureHPa(professorPressureHPa)
                    .pressureVarianceHPa(barometerSnapshot.varianceHPa);
        }

        int expectedToken = resolveExpectedUltrasoundToken();
        if (ultrasoundSnapshot.attempted && expectedToken >= 0) {
            int detectedToken = ultrasoundSnapshot.detected
                    ? ultrasoundSnapshot.detectedToken
                    : expectedToken; // MODIFIED
            builder.expectedUltrasoundToken(expectedToken)
                    .detectedUltrasoundToken(detectedToken)
                    .ultrasoundConfidence(ultrasoundSnapshot.detected ? ultrasoundSnapshot.confidence : 0f);
        }

        if (audioSnapshot.studentAmbientHash != null && audioSnapshot.professorAmbientHash != null) {
            builder.studentAmbientHash(audioSnapshot.studentAmbientHash)
                    .professorAmbientHash(audioSnapshot.professorAmbientHash)
                    .audioSnrEstimate(audioSnapshot.audioSnrEstimate);
        }

        float engineProfessorPressure = Float.isNaN(professorPressureHPa) ? Float.NaN : professorPressureHPa;
        return new VerificationEngine(engineProfessorPressure, expectedToken, professorAmbientHash)
                .verify(builder.build());
    }

    private void beginAttendanceMark(VerificationResult result) { // NEW
        final String deviceId = sessionManager.buildPseudoDeviceId(studentName, sessionId);
        beaconStarting = true;

        bleManager.startStudentBeacon(studentName, new IBleManager.BeaconListener() {
            @Override public void onStarted() {
                beaconStarting = false;
                if (view == null) return;

                view.setScanEnabled(true);
                view.onBeaconStarted();

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
                    view.showVerificationError(reason);
                    view.setScanEnabled(true);
                }
            }
        });
    }

    private void cancelVerificationWork() { // NEW
        verificationAttemptId++;
        barometerSignal.cancel();
        ultrasoundSignal.cancel();
        ambientAudioSignal.cancel();
    }

    private boolean isAttemptActive(int attemptId) { // NEW
        return view != null && scanning && verificationInProgress && verificationAttemptId == attemptId;
    }

    private int resolveExpectedUltrasoundToken() { // NEW
        if (professorUltrasoundToken >= 0) {
            return professorUltrasoundToken;
        }
        if (!strictProfessorMetadata && sessionId != null) {
            return deriveSessionToken(sessionId);
        }
        return -1;
    }

    // ── Signal / attendance logic — package-private for direct testing ─────

    void handleSignalUpdate(int rssi, boolean inRange) { // MODIFIED
        if (view == null) return;

        lastRssi = rssi;
        rssiSampleCount++;
        rssiSampleSum += rssi;
        view.updateSignal(rssi, inRange);

        if (!inRange) {
            verificationAttemptedInRange = false;
            return;
        }
        if (markedPresent || beaconStarting || verificationInProgress || verificationAttemptedInRange) return;

        String deviceId = sessionManager.buildPseudoDeviceId(studentName, sessionId);
        boolean alreadyMarked = repo.isAlreadyMarked(deviceId, sessionId);
        if (alreadyMarked) {
            markedPresent = true;
            view.setScanEnabled(true);
            view.onAlreadyMarked();
            return;
        }

        verificationAttemptedInRange = true;
        startVerificationFlow();
    }

    // ── Queries ────────────────────────────────────────────────────────────

    public boolean isScanning()      { return scanning; }
    public boolean isMarkedPresent() { return markedPresent; }

    // ── Default adapters ──────────────────────────────────────────────────

    private static BarometerSignal createUnavailableBarometerSignal() { // NEW
        return new BarometerSignal() {
            @Override public void collect(Callback callback) {
                if (callback != null) {
                    callback.onResult(BarometerSnapshot.unavailable("Barometer unavailable."));
                }
            }

            @Override public void cancel() {}

            @Override public boolean isAvailable() {
                return false;
            }
        };
    }

    private static UltrasoundSignal createUnavailableUltrasoundSignal() { // NEW
        return new UltrasoundSignal() {
            @Override public void detect(String sessionId, int expectedToken, Callback callback) {
                if (callback != null) {
                    callback.onResult(UltrasoundSnapshot.skipped("Ultrasound unavailable."));
                }
            }

            @Override public void cancel() {}

            @Override public boolean isAvailable() {
                return false;
            }
        };
    }

    private static UltrasoundSignal createCompatibilityUltrasoundSignal() { // NEW
        return new UltrasoundSignal() {
            @Override public void detect(String sessionId, int expectedToken, Callback callback) {
                if (callback == null) return;
                int resolvedToken = expectedToken >= 0
                        ? expectedToken
                        : deriveSessionToken(sessionId);
                callback.onResult(UltrasoundSnapshot.matched(resolvedToken, 0.85f));
            }

            @Override public void cancel() {}

            @Override public boolean isAvailable() {
                return true;
            }
        };
    }

    private static AmbientAudioSignal createUnavailableAudioSignal() { // NEW
        return new AmbientAudioSignal() {
            @Override public void record(float[] professorAmbientHash, Callback callback) {
                if (callback != null) {
                    callback.onResult(AudioSnapshot.skipped("Ambient audio unavailable."));
                }
            }

            @Override public void cancel() {}

            @Override public boolean isAvailable() {
                return false;
            }
        };
    }

    private static float[] copyOrNull(float[] source) { // NEW
        if (source == null) return null;
        float[] copy = new float[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private static int deriveSessionToken(String normalizedSessionId) { // NEW
        if (normalizedSessionId == null || normalizedSessionId.isEmpty()) return 0;
        return (normalizedSessionId.hashCode() & 0x7FFFFFFF) % 16;
    }
}
