package com.example.digitalsphere.presentation;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler; // NEW
import android.os.Looper; // NEW
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.digitalsphere.R;
import com.example.digitalsphere.contract.IStudentView;
import com.example.digitalsphere.data.audio.AmbientAudioRecorder; // NEW
import com.example.digitalsphere.data.audio.UltrasoundDetector;
import com.example.digitalsphere.data.sensor.BarometerReader;
import com.example.digitalsphere.domain.verification.VerificationResult; // NEW
import com.example.digitalsphere.domain.verification.VerificationStatus; // NEW
import com.example.digitalsphere.presenter.StudentPresenter;
import com.google.android.material.snackbar.Snackbar;
import java.util.Locale;

/**
 * Thin view — zero business logic.
 * All decisions delegated to StudentPresenter.
 */
public class StudentActivity extends AppCompatActivity implements IStudentView {

    private static final String EXTRA_AUTO_NAME = "auto_name"; // NEW
    private static final String EXTRA_AUTO_SESSION = "auto_session"; // NEW
    private static final String EXTRA_AUTO_START = "auto_start"; // NEW

    private static final int RSSI_MIN = -100;
    private static final int RSSI_MAX = -30;
    private static final int BAROMETER_SAMPLE_TARGET = 5; // NEW
    private static final long BAROMETER_TIMEOUT_MS = 1200L; // NEW
    private static final long ULTRASOUND_TIMEOUT_MS = 4000L; // NEW

    private StudentPresenter presenter;

    private boolean hasBarometerHardware; // NEW
    private StudentPresenter.BarometerSignal barometerSignal; // NEW
    private StudentPresenter.UltrasoundSignal ultrasoundSignal; // NEW
    private StudentPresenter.AmbientAudioSignal ambientAudioSignal; // NEW

    // Views
    private EditText etName;
    private EditText etSessionId;
    private LinearLayout layoutSignal;
    private TextView tvSignalLabel;
    private ProgressBar pbSignal;
    private TextView tvRssi;
    private TextView tvStatus;
    private Button btnScan;

    // MODIFIED: Verification panel views are now driven by the sequential verification flow.
    private TextView tvVerifyFloor;
    private TextView tvVerifyRoom;
    private TextView tvVerifyAudio;
    private TextView tvVerifyBle;
    private TextView tvVerifyOverall;
    private TextView tvVerifyReason;

    private LinearLayout bannerSensorCaps;
    private TextView tvSensorCapsDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student);

        etName = findViewById(R.id.et_student_name);
        etSessionId = findViewById(R.id.et_session_id);
        layoutSignal = findViewById(R.id.layout_signal);
        tvSignalLabel = findViewById(R.id.tv_signal_label);
        pbSignal = findViewById(R.id.pb_signal);
        tvRssi = findViewById(R.id.tv_rssi);
        tvStatus = findViewById(R.id.tv_status);
        btnScan = findViewById(R.id.btn_scan);

        tvVerifyFloor = findViewById(R.id.tv_verify_floor);
        tvVerifyRoom = findViewById(R.id.tv_verify_room);
        tvVerifyAudio = findViewById(R.id.tv_verify_audio);
        tvVerifyBle = findViewById(R.id.tv_verify_ble);
        tvVerifyOverall = findViewById(R.id.tv_verify_overall);
        tvVerifyReason = findViewById(R.id.tv_verify_reason);

        bannerSensorCaps = findViewById(R.id.banner_sensor_caps);
        tvSensorCapsDetail = findViewById(R.id.tv_sensor_caps_detail);

        hasBarometerHardware = detectBarometerHardware(); // NEW
        barometerSignal = createBarometerSignal(); // NEW
        ultrasoundSignal = createUltrasoundSignal(); // NEW
        ambientAudioSignal = createAmbientAudioSignal(); // NEW

        presenter = new StudentPresenter(this, barometerSignal, ultrasoundSignal, ambientAudioSignal); // MODIFIED
        presenter.attach(this);

        resetVerificationPanel();
        showSensorCapabilities(hasBarometerHardware, hasMicPermission(), hasMicPermission()); // MODIFIED

        btnScan.setOnClickListener(v -> {
            if (presenter.isScanning()) {
                presenter.stopScan();
                btnScan.setText("📡 Scan for Class");
                resetVerificationPanel();
            } else {
                resetVerificationPanel();
                presenter.startScan(
                        etName.getText().toString().trim(),
                        etSessionId.getText().toString().trim(),
                        hasMicPermission());
            }
        });

        applyAutomationExtras(); // NEW
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        presenter.detach();
    }

    // ── Sensor adapters ───────────────────────────────────────────────────

    private boolean detectBarometerHardware() { // NEW
        BarometerReader probe = new BarometerReader(this, new BarometerReader.BarometerCallback() {
            @Override public void onPressureReading(float hPa) {}
            @Override public void onBarometerUnavailable() {}
        });
        return probe.isAvailable();
    }

    private StudentPresenter.BarometerSignal createBarometerSignal() { // NEW
        return new StudentPresenter.BarometerSignal() {
            private final Handler handler = new Handler(Looper.getMainLooper());
            private final float[] samples = new float[BAROMETER_SAMPLE_TARGET];
            private int sampleCount = 0;
            private BarometerReader activeReader;
            private Callback activeCallback;
            private final Runnable finishRunnable = this::finishCollection;

            @Override
            public void collect(Callback callback) {
                cancel();
                activeCallback = callback;
                sampleCount = 0;

                if (!hasBarometerHardware) {
                    deliver(StudentPresenter.BarometerSnapshot.unavailable("Student barometer unavailable."));
                    return;
                }

                activeReader = new BarometerReader(StudentActivity.this, new BarometerReader.BarometerCallback() {
                    @Override
                    public void onPressureReading(float hPa) {
                        if (sampleCount < samples.length) {
                            samples[sampleCount++] = hPa;
                        } else {
                            System.arraycopy(samples, 1, samples, 0, samples.length - 1);
                            samples[samples.length - 1] = hPa;
                        }

                        if (sampleCount >= BAROMETER_SAMPLE_TARGET) {
                            finishCollection();
                        }
                    }

                    @Override
                    public void onBarometerUnavailable() {
                        deliver(StudentPresenter.BarometerSnapshot.unavailable("Student barometer unavailable."));
                    }
                });

                activeReader.startReading();
                handler.postDelayed(finishRunnable, BAROMETER_TIMEOUT_MS);
            }

            @Override
            public void cancel() {
                handler.removeCallbacks(finishRunnable);
                if (activeReader != null) {
                    activeReader.stopReading();
                    activeReader = null;
                }
                activeCallback = null;
                sampleCount = 0;
            }

            @Override
            public boolean isAvailable() {
                return hasBarometerHardware;
            }

            private void finishCollection() {
                if (activeCallback == null) return;
                if (sampleCount == 0) {
                    deliver(StudentPresenter.BarometerSnapshot.unavailable("No pressure samples collected."));
                    return;
                }

                float mean = 0f;
                for (int i = 0; i < sampleCount; i++) {
                    mean += samples[i];
                }
                mean /= sampleCount;

                float variance = 0f;
                for (int i = 0; i < sampleCount; i++) {
                    float diff = samples[i] - mean;
                    variance += diff * diff;
                }
                variance /= sampleCount;

                deliver(StudentPresenter.BarometerSnapshot.available(mean, variance));
            }

            private void deliver(StudentPresenter.BarometerSnapshot snapshot) {
                Callback callback = activeCallback;
                cancel();
                if (callback != null) {
                    callback.onResult(snapshot);
                }
            }
        };
    }

    private StudentPresenter.UltrasoundSignal createUltrasoundSignal() { // NEW
        return new StudentPresenter.UltrasoundSignal() {
            private final Handler handler = new Handler(Looper.getMainLooper());
            private UltrasoundDetector activeDetector;
            private Callback activeCallback;
            private final Runnable timeoutRunnable = () ->
                    deliver(StudentPresenter.UltrasoundSnapshot.failed("Timed out waiting for professor ultrasound."));

            @Override
            public void detect(String sessionId, int expectedToken, Callback callback) {
                cancel();
                activeCallback = callback;

                if (!hasMicPermission()) {
                    deliver(StudentPresenter.UltrasoundSnapshot.failed("Microphone permission missing."));
                    return;
                }

                activeDetector = new UltrasoundDetector(sessionId, new UltrasoundDetector.DetectorCallback() {
                    @Override
                    public void onTokenDecoded(int token, double magnitude) {
                        deliver(StudentPresenter.UltrasoundSnapshot.matched(
                                token,
                                normalizeUltrasoundConfidence(magnitude)));
                    }

                    @Override
                    public void onSearching() {
                        // Presenter owns the step state; no extra UI work needed here.
                    }

                    @Override
                    public void onDetectionError(String reason) {
                        deliver(StudentPresenter.UltrasoundSnapshot.failed(reason));
                    }
                });

                activeDetector.start();
                handler.postDelayed(timeoutRunnable, ULTRASOUND_TIMEOUT_MS);
            }

            @Override
            public void cancel() {
                handler.removeCallbacks(timeoutRunnable);
                if (activeDetector != null) {
                    activeDetector.stop();
                    activeDetector = null;
                }
                activeCallback = null;
            }

            @Override
            public boolean isAvailable() {
                return hasMicPermission();
            }

            private void deliver(StudentPresenter.UltrasoundSnapshot snapshot) {
                Callback callback = activeCallback;
                cancel();
                if (callback != null) {
                    callback.onResult(snapshot);
                }
            }
        };
    }

    private StudentPresenter.AmbientAudioSignal createAmbientAudioSignal() { // NEW
        return new StudentPresenter.AmbientAudioSignal() {
            private AmbientAudioRecorder activeRecorder;
            private Callback activeCallback;

            @Override
            public void record(float[] professorAmbientHash, Callback callback) {
                cancel();
                activeCallback = callback;

                if (professorAmbientHash == null || professorAmbientHash.length == 0) {
                    deliver(StudentPresenter.AudioSnapshot.skipped("Student microphone is ready, but professor ambient audio reference is unavailable.")); // MODIFIED
                    return;
                }

                if (!hasMicPermission()) {
                    deliver(StudentPresenter.AudioSnapshot.skipped("Microphone permission missing."));
                    return;
                }

                activeRecorder = new AmbientAudioRecorder();
                float[] professorCopy = copyArray(professorAmbientHash);
                activeRecorder.recordAndFingerprint(new AmbientAudioRecorder.RecordingCallback() {
                    @Override
                    public void onFingerprintReady(float[] hash) {
                        deliver(StudentPresenter.AudioSnapshot.captured(
                                hash,
                                professorCopy,
                                estimateAudioSnr(hash)));
                    }

                    @Override
                    public void onRecordingFailed(String reason) {
                        deliver(StudentPresenter.AudioSnapshot.skipped(reason));
                    }
                });
            }

            @Override
            public void cancel() {
                if (activeRecorder != null) {
                    activeRecorder.shutdown();
                    activeRecorder = null;
                }
                activeCallback = null;
            }

            @Override
            public boolean isAvailable() {
                return hasMicPermission();
            }

            private void deliver(StudentPresenter.AudioSnapshot snapshot) {
                Callback callback = activeCallback;
                cancel();
                if (callback != null) {
                    callback.onResult(snapshot);
                }
            }
        };
    }

    // ── UI helpers ────────────────────────────────────────────────────────

    private void setVerifyStatus(TextView tv, String text, String hexColor) {
        tv.setText(text);
        try {
            tv.setTextColor(Color.parseColor(hexColor));
        } catch (IllegalArgumentException e) {
            tv.setTextColor(Color.parseColor("#888888"));
        }
    }

    private void resetVerificationPanel() { // MODIFIED
        setVerifyStatus(tvVerifyFloor, "—", "#888888");
        setVerifyStatus(tvVerifyRoom, "—", "#888888");
        setVerifyStatus(tvVerifyAudio, "—", "#888888");
        setVerifyStatus(tvVerifyBle, "—", "#888888");
        setVerifyStatus(tvVerifyOverall, "—", "#888888");
        tvVerifyReason.setText("");
        tvVerifyReason.setVisibility(View.GONE);
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private float normalizeUltrasoundConfidence(double magnitude) { // NEW
        double confidence = magnitude / (1500.0 * 4.0);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        return (float) confidence;
    }

    private float estimateAudioSnr(float[] hash) { // NEW
        if (hash == null || hash.length == 0) return 0f;

        float max = 0f;
        float mean = 0f;
        for (float value : hash) {
            max = Math.max(max, value);
            mean += value;
        }
        mean /= hash.length;

        if (mean <= 0f) return 0f;
        double ratio = (max + 1e-6) / ((mean * 0.5f) + 1e-6);
        return (float) Math.max(0.0, Math.min(20.0, 20.0 * Math.log10(ratio)));
    }

    private float[] copyArray(float[] source) { // NEW
        if (source == null) return null;
        float[] copy = new float[source.length];
        System.arraycopy(source, 0, copy, 0, source.length);
        return copy;
    }

    private void runOnUiThreadSafe(Runnable action) { // NEW
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            runOnUiThread(action);
        }
    }

    private void applyAutomationExtras() { // NEW
        String autoName = getIntent() != null ? getIntent().getStringExtra(EXTRA_AUTO_NAME) : null;
        String autoSession = getIntent() != null ? getIntent().getStringExtra(EXTRA_AUTO_SESSION) : null;
        boolean autoStart = getIntent() != null && getIntent().getBooleanExtra(EXTRA_AUTO_START, false);

        if (autoName != null && !autoName.trim().isEmpty()) {
            etName.setText(autoName.trim());
        }
        if (autoSession != null && !autoSession.trim().isEmpty()) {
            etSessionId.setText(autoSession.trim());
        }
        if (autoStart) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> btnScan.performClick(), 1200L);
        }
    }

    private String formatPercent(float value) { // NEW
        return String.format(Locale.US, "%.0f%%", value * 100f);
    }

    private void renderOverallOutcome(String status, float fusionScore, String reason) { // NEW
        String scoreText = String.format(Locale.US, "%.0f%%", fusionScore * 100f);

        switch (status) {
            case "PRESENT":
                setVerifyStatus(tvVerifyOverall, "✅ Verified (" + scoreText + ")", "#2ECC71");
                break;
            case "FLAGGED":
                setVerifyStatus(tvVerifyOverall, "⚠️ Flagged (" + scoreText + ")", "#F39C12");
                break;
            case "CONFLICT":
                setVerifyStatus(tvVerifyOverall, "⚠️ Signal conflict (" + scoreText + ")", "#E67E22");
                break;
            case "REJECTED_FLOOR":
                setVerifyStatus(tvVerifyOverall, "❌ Rejected — wrong floor", "#E74C3C");
                break;
            case "REJECTED_ROOM":
                setVerifyStatus(tvVerifyOverall, "❌ Rejected — room mismatch", "#E74C3C");
                break;
            default:
                setVerifyStatus(tvVerifyOverall, "❌ Not verified (" + scoreText + ")", "#E74C3C");
                break;
        }

        if (reason == null || reason.trim().isEmpty()) {
            tvVerifyReason.setVisibility(View.GONE);
            tvVerifyReason.setText("");
        } else {
            tvVerifyReason.setVisibility(View.VISIBLE);
            tvVerifyReason.setText(reason);
        }
    }

    // ── IStudentView ──────────────────────────────────────────────────────

    @Override
    public void updateSignal(int rssi, boolean inRange) { // MODIFIED
        runOnUiThreadSafe(() -> {
            int clamped = Math.max(RSSI_MIN, Math.min(RSSI_MAX, rssi));
            int progress = (int) (((float) (clamped - RSSI_MIN) / (RSSI_MAX - RSSI_MIN)) * 100);
            pbSignal.setProgress(progress);

            int color = inRange ? 0xFF27AE60 : 0xFFE74C3C;
            pbSignal.setProgressTintList(ColorStateList.valueOf(color));

            tvRssi.setText(rssi + " dBm");
            tvSignalLabel.setText(inRange ? "Professor in range — running verification..." : "Searching for professor...");

            if (rssi >= -50) {
                setVerifyStatus(tvVerifyBle, "Strong (" + rssi + " dBm)", "#2ECC71");
            } else if (rssi >= -75) {
                setVerifyStatus(tvVerifyBle, "Moderate (" + rssi + " dBm)", "#F39C12");
            } else {
                setVerifyStatus(tvVerifyBle, "Weak (" + rssi + " dBm)", "#E74C3C");
            }
        });
    }

    @Override
    public void onBeaconStarted() {
        runOnUiThreadSafe(() -> {
            tvStatus.setText("Status: Broadcasting your name...");
            btnScan.setText("⏹ Stop Scanning");
        }); // MODIFIED
    }

    @Override
    public void onAttendanceMarked() {
        runOnUiThreadSafe(() -> {
            tvStatus.setText("Status: ✅ Attendance marked!");
            tvSignalLabel.setText("Attendance recorded");
            Toast.makeText(this, "✅ Attendance marked successfully!", Toast.LENGTH_LONG).show();
        }); // MODIFIED
    }

    @Override
    public void onAlreadyMarked() {
        runOnUiThreadSafe(() -> {
            tvStatus.setText("Status: Already marked for this session.");
            Toast.makeText(this, "Already marked present for this session.", Toast.LENGTH_SHORT).show();
        }); // MODIFIED
    }

    @Override
    public void showStatus(String message) {
        runOnUiThreadSafe(() -> tvStatus.setText("Status: " + message)); // MODIFIED
    }

    @Override
    public void showError(String message) {
        runOnUiThreadSafe(() -> {
            Snackbar.make(btnScan, message, Snackbar.LENGTH_LONG).show();
            tvStatus.setText("Status: " + message);
        }); // MODIFIED
    }

    @Override
    public void showVerificationStep(String stepName, int stepNumber, int totalSteps) { // NEW
        runOnUiThreadSafe(() -> {
            tvStatus.setText("Status: Step " + stepNumber + "/" + totalSteps + ": " + stepName);
            if (stepNumber == 1) {
                setVerifyStatus(tvVerifyBle, "Searching...", "#888888");
            } else if (stepNumber == 2) {
                setVerifyStatus(tvVerifyFloor, "Checking floor...", "#888888");
            } else if (stepNumber == 3) {
                setVerifyStatus(tvVerifyRoom, "Listening for room tone...", "#888888");
            } else if (stepNumber == 4) {
                setVerifyStatus(tvVerifyAudio, "Audio fingerprint...", "#888888");
            }
        }); // MODIFIED
    }

    @Override
    public void showVerificationResult(VerificationResult result) { // NEW
        runOnUiThreadSafe(() -> {
            if (result == null) return;

            if (result.getStatus() == VerificationStatus.REJECTED_FLOOR) {
                setVerifyStatus(tvVerifyFloor, "❌ Wrong floor", "#E74C3C");
            } else if (result.getSvsBaro() > 0f || result.getPresScoreBaro() > 0f) {
                setVerifyStatus(tvVerifyFloor,
                        "✅ Same floor (" + formatPercent(result.getPresScoreBaro()) + ")",
                        "#2ECC71");
            } else {
                setVerifyStatus(tvVerifyFloor, "Skipped - floor reference unavailable", "#F39C12");
            }

            if (result.getStatus() == VerificationStatus.REJECTED_ROOM) {
                setVerifyStatus(tvVerifyRoom, "❌ Room mismatch", "#E74C3C");
            } else if (result.getSvsUltra() > 0f || result.getPresScoreUltra() > 0f) {
                setVerifyStatus(tvVerifyRoom,
                        "✅ Ultrasound locked (" + formatPercent(result.getPresScoreUltra()) + ")",
                        "#2ECC71");
            } else {
                setVerifyStatus(tvVerifyRoom, "Skipped - ultrasound unavailable or not required", "#F39C12");
            }

            if (result.getSvsAudio() > 0f || result.getPresScoreAudio() > 0f) {
                setVerifyStatus(tvVerifyAudio,
                        "✅ Audio matched (" + formatPercent(result.getPresScoreAudio()) + ")",
                        "#2ECC71");
            } else {
                setVerifyStatus(tvVerifyAudio, "Skipped - student mic ready; professor audio reference missing", "#F39C12"); // MODIFIED
            }

            renderOverallOutcome(
                    result.getStatus().name(),
                    result.getFusionScore(),
                    result.getRejectionReason());
        }); // MODIFIED
    }

    @Override
    public void showVerificationError(String error) { // NEW
        runOnUiThreadSafe(() -> {
            Snackbar.make(btnScan, error, Snackbar.LENGTH_LONG).show();
            tvStatus.setText("Status: Verification error: " + error);
        }); // MODIFIED
    }

    @Override
    public void setScanEnabled(boolean enabled) { // MODIFIED
        runOnUiThreadSafe(() -> {
            btnScan.setEnabled(enabled);
            if (enabled) {
                btnScan.setText(presenter != null && presenter.isScanning()
                        ? "⏹ Stop Scanning"
                        : "📡 Scan for Class");
            }
        });
    }

    @Override
    public void setSignalPanelVisible(boolean visible) {
        runOnUiThreadSafe(() -> layoutSignal.setVisibility(visible ? View.VISIBLE : View.GONE)); // MODIFIED
    }

    @Override
    public void showSensorCapabilities(boolean hasBarometer, boolean hasAudio, boolean hasUltrasound) { // MODIFIED
        runOnUiThreadSafe(() -> {
            StringBuilder sb = new StringBuilder();

            sb.append(hasBarometer
                    ? "🌡️ Floor check: Ready\n"
                    : "🌡️ Floor check: Skipped - no barometer sensor\n");

            sb.append(hasUltrasound
                    ? "🔊 Room check: Ready\n"
                    : "🔊 Room check: Skipped - mic unavailable\n");

            if (hasAudio) {
                sb.append("🎵 Audio check: Student mic ready; compares when professor reference is available\n"); // MODIFIED
            } else {
                sb.append("🎵 Audio check: Skipped - mic unavailable\n");
            }

            sb.append("📡 BLE signal: Ready");

            int activeCount = 1;
            if (hasBarometer) activeCount++;
            if (hasUltrasound) activeCount++;

            sb.append("\n\nLive flow uses BLE + barometer + ultrasound, and now includes ")
              .append("ambient audio when the professor broadcasts a room reference.");

            tvSensorCapsDetail.setText(sb.toString());
            bannerSensorCaps.setVisibility(View.VISIBLE);
        });
    }

    @Override
    public void showVerificationOutcome(String status, float fusionScore, String reason) { // MODIFIED
        runOnUiThreadSafe(() -> renderOverallOutcome(status, fusionScore, reason));
    }
}
