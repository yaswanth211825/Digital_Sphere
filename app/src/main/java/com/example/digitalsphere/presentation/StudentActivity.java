package com.example.digitalsphere.presentation;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
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
import com.example.digitalsphere.data.audio.UltrasoundDetector;
import com.example.digitalsphere.data.sensor.BarometerReader;
import com.example.digitalsphere.data.sensor.ResearchLogger;
import com.example.digitalsphere.domain.SessionManager;
import com.example.digitalsphere.presenter.StudentPresenter;
import com.google.android.material.snackbar.Snackbar;
import java.util.Locale;

/**
 * Thin view — zero business logic.
 * All decisions delegated to StudentPresenter.
 *
 * SPRINT-1-UI: Added BarometerReader integration for floor verification,
 * 4-row verification status panel, and proper error handling with Snackbar.
 */
public class StudentActivity extends AppCompatActivity implements IStudentView {

    private static final int RSSI_MIN = -100;
    private static final int RSSI_MAX =  -30;

    // SPRINT-1-UI: Stability detection — variance threshold across recent readings
    private static final float STABILITY_VARIANCE_THRESHOLD = 0.15f;
    private static final int   STABILITY_WINDOW_SIZE        = 3;

    private StudentPresenter presenter;
    private final SessionManager sessionManager = new SessionManager();

    // SPRINT-1-UI: Barometer integration
    private BarometerReader barometerReader;
    private float           currentPressureHPa  = 0.0f;
    private boolean         barometerAvailable   = false;
    private boolean         floorCheckDone       = false;
    private UltrasoundDetector ultrasoundDetector;

    // SPRINT-1-UI: Stability tracking — ring buffer of last N readings
    private final float[] recentReadings = new float[STABILITY_WINDOW_SIZE];
    private int     readingCount  = 0;
    private boolean stableReading = false;

    // Views
    private EditText     etName;
    private EditText     etSessionId;
    private LinearLayout layoutSignal;
    private TextView     tvSignalLabel;
    private ProgressBar  pbSignal;
    private TextView     tvRssi;
    private TextView     tvStatus;
    private Button       btnScan;

    // SPRINT-1-UI: Verification panel views
    private TextView tvVerifyFloor;
    private TextView tvVerifyRoom;
    private TextView tvVerifyAudio;
    private TextView tvVerifyBle;
    private TextView tvVerifyOverall;
    private TextView tvVerifyReason;

    // Sensor capability banner
    private LinearLayout bannerSensorCaps;
    private TextView     tvSensorCapsDetail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_student);

        etName       = findViewById(R.id.et_student_name);
        etSessionId  = findViewById(R.id.et_session_id);
        layoutSignal = findViewById(R.id.layout_signal);
        tvSignalLabel= findViewById(R.id.tv_signal_label);
        pbSignal     = findViewById(R.id.pb_signal);
        tvRssi       = findViewById(R.id.tv_rssi);
        tvStatus     = findViewById(R.id.tv_status);
        btnScan      = findViewById(R.id.btn_scan);

        // SPRINT-1-UI: Verification panel
        tvVerifyFloor   = findViewById(R.id.tv_verify_floor);
        tvVerifyRoom    = findViewById(R.id.tv_verify_room);
        tvVerifyAudio   = findViewById(R.id.tv_verify_audio);
        tvVerifyBle     = findViewById(R.id.tv_verify_ble);
        tvVerifyOverall = findViewById(R.id.tv_verify_overall);
        tvVerifyReason  = findViewById(R.id.tv_verify_reason);

        // Sensor capability banner
        bannerSensorCaps  = findViewById(R.id.banner_sensor_caps);
        tvSensorCapsDetail = findViewById(R.id.tv_sensor_caps_detail);

        presenter = new StudentPresenter(this);
        presenter.attach(this);

        // SPRINT-1-UI: Initialize barometer
        initBarometer();

        btnScan.setOnClickListener(v -> {
            if (presenter.isScanning()) {
                presenter.stopScan();
                btnScan.setText("📡 Scan for Class");
                // SPRINT-1-UI: Reset verification panel on stop
                resetVerificationPanel();
            } else {
                // SPRINT-1-UI: Reset floor check state for new scan
                floorCheckDone = false;
                resetVerificationPanel();

                // If barometer is missing, immediately show "Skipped" instead
                // of briefly flashing "Checking..." before overwriting.
                if (!barometerAvailable) {
                    setVerifyStatus(tvVerifyFloor,
                            "Skipped — no sensor, continuing with other signals",
                            "#F39C12");
                } else {
                    setVerifyStatus(tvVerifyFloor, "Checking...", "#888888");
                }

                presenter.startScan(
                        etName.getText().toString().trim(),
                        etSessionId.getText().toString().trim(),
                        hasMicPermission());

                if (presenter.isScanning() && hasMicPermission()) {
                    startUltrasoundDetector(sessionManager.createSessionId(
                            etSessionId.getText().toString().trim()));
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // SPRINT-1-UI: Stop barometer to prevent memory leak
        if (barometerReader != null) {
            barometerReader.stopReading();
        }
        stopUltrasoundDetector();
        presenter.detach();
    }

    // ── SPRINT-1-UI: Barometer setup ────────────────────────────────────────

    private void initBarometer() {
        barometerReader = new BarometerReader(this, new BarometerReader.BarometerCallback() {
            @Override
            public void onPressureReading(float hPa) {
                currentPressureHPa = hPa;
                barometerAvailable = true;

                // SPRINT-1-UI: Track stability via ring buffer
                trackStability(hPa);

                // SPRINT-1-UI: Research logging
                ResearchLogger.logPressureReading("STUDENT", hPa);
            }

            @Override
            public void onBarometerUnavailable() {
                barometerAvailable  = false;
                currentPressureHPa  = 0.0f;

                // Show clear "skipped but continuing" message — not a blocker
                setVerifyStatus(tvVerifyFloor,
                        "Skipped — no sensor, continuing with other signals",
                        "#F39C12");

                ResearchLogger.logBarometerAvailability(false, "STUDENT");
                ResearchLogger.logError("BARO_UNAVAILABLE",
                        "Student device has no barometer — floor check skipped, other signals continue");
            }
        });

        if (barometerReader.isAvailable()) {
            ResearchLogger.logBarometerAvailability(true, "STUDENT");
            barometerReader.startReading();
        } else {
            barometerReader.startReading();  // triggers onBarometerUnavailable()
        }

        // Show sensor capabilities banner so the student knows what checks will run.
        // Audio and ultrasound availability will be refined later when permissions
        // are checked; for now assume available if RECORD_AUDIO was granted.
        boolean hasAudio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
        showSensorCapabilities(barometerReader.isAvailable(), hasAudio, hasAudio);
    }

    // SPRINT-1-UI: Track stability of barometer readings
    private void trackStability(float hPa) {
        int idx = readingCount % STABILITY_WINDOW_SIZE;
        recentReadings[idx] = hPa;
        readingCount++;

        if (readingCount >= STABILITY_WINDOW_SIZE) {
            float variance = computeVariance();
            stableReading = (variance <= STABILITY_VARIANCE_THRESHOLD);
            if (!stableReading) {
                ResearchLogger.logUnstableReading(variance);
            }
        }
    }

    private float computeVariance() {
        float sum = 0;
        for (float r : recentReadings) sum += r;
        float mean = sum / STABILITY_WINDOW_SIZE;
        float varSum = 0;
        for (float r : recentReadings) varSum += (r - mean) * (r - mean);
        return varSum / STABILITY_WINDOW_SIZE;
    }

    // ── SPRINT-1-UI: Floor verification logic ───────────────────────────────

    /**
     * Called by the presenter (via the view interface) after BLE detects the
     * professor's advertisement and extracts the pressure from the payload.
     * Also called internally once student barometer stabilises.
     */
    public void performFloorCheck(float professorHPa) {
        if (floorCheckDone) return;

        // Professor has no barometer (sentinel = 0.0f) — skip but continue
        if (professorHPa == 0.0f) {
            setVerifyStatus(tvVerifyFloor,
                    "Skipped — professor device has no floor sensor",
                    "#F39C12");
            floorCheckDone = true;
            ResearchLogger.logError("PROF_BARO_UNAVAILABLE",
                    "Professor pressure is 0.0 — barometer absent on professor device, continuing");
            return;
        }

        // Student has no barometer — skip but continue
        if (!barometerAvailable) {
            setVerifyStatus(tvVerifyFloor,
                    "Skipped — no sensor, continuing with other signals",
                    "#F39C12");
            floorCheckDone = true;
            return;
        }

        // ERROR STATE 4 — Unstable reading
        if (readingCount >= STABILITY_WINDOW_SIZE && !stableReading) {
            float variance = computeVariance();
            setVerifyStatus(tvVerifyFloor,
                    "⚠️ Unstable reading — please stand still and retry",
                    "#F39C12");
            ResearchLogger.logUnstableReading(variance);
            // Do NOT set floorCheckDone — allow retry on next reading
            return;
        }

        // Not enough readings yet
        if (readingCount < STABILITY_WINDOW_SIZE) {
            setVerifyStatus(tvVerifyFloor, "Checking...", "#888888");
            return;
        }

        // Perform the actual floor check
        boolean sameFloor = BarometerReader.isSameFloor(professorHPa, currentPressureHPa);
        float diff = Math.abs(professorHPa - currentPressureHPa);
        float metres = BarometerReader.pressureToAltitudeDiff(diff);

        // Research logging
        ResearchLogger.logFloorCheck(professorHPa, currentPressureHPa, sameFloor);

        floorCheckDone = true;

        if (sameFloor) {
            // SUCCESS — same floor
            setVerifyStatus(tvVerifyFloor, "✅ Same floor", "#2ECC71");
        } else {
            // ERROR STATE 2 — Wrong floor detected
            String detail = String.format(Locale.US,
                    "❌ Wrong floor detected (Δ%.2f hPa ≈ %.1fm difference)",
                    diff, metres);
            setVerifyStatus(tvVerifyFloor, detail, "#E74C3C");

            // Snackbar for actionable error — stays visible unlike Toast
            Snackbar.make(btnScan,
                    "You appear to be on a different floor. Please move to the correct classroom floor.",
                    Snackbar.LENGTH_LONG).show();

            ResearchLogger.logError("WRONG_FLOOR",
                    String.format(Locale.US, "diff=%.2f hPa, metres=%.1f", diff, metres));
        }
    }

    // ── SPRINT-1-UI: Verification panel helpers ─────────────────────────────

    private void setVerifyStatus(TextView tv, String text, String hexColor) {
        tv.setText(text);
        try {
            tv.setTextColor(Color.parseColor(hexColor));
        } catch (IllegalArgumentException e) {
            tv.setTextColor(Color.parseColor("#888888"));
        }
    }

    private void resetVerificationPanel() {
        setVerifyStatus(tvVerifyFloor,   "—", "#888888");
        setVerifyStatus(tvVerifyRoom,    "—", "#888888");
        setVerifyStatus(tvVerifyAudio,   "—", "#888888");
        setVerifyStatus(tvVerifyBle,     "—", "#888888");
        setVerifyStatus(tvVerifyOverall, "—", "#888888");
        tvVerifyReason.setText("");
        tvVerifyReason.setVisibility(View.GONE);
        floorCheckDone = false;
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    private void startUltrasoundDetector(String sessionId) {
        stopUltrasoundDetector();

        ultrasoundDetector = new UltrasoundDetector(sessionId, new UltrasoundDetector.DetectorCallback() {
            @Override
            public void onTokenDecoded(int token, double magnitude) {
                float confidence = normalizeUltrasoundConfidence(magnitude);
                presenter.onUltrasoundDetected(token, confidence);
                runOnUiThread(() -> setVerifyStatus(
                        tvVerifyRoom,
                        "✅ Ultrasound locked (token " + token + ")",
                        "#2ECC71"));
            }

            @Override
            public void onSearching() {
                runOnUiThread(() -> {
                    CharSequence current = tvVerifyRoom.getText();
                    if (current == null || "—".contentEquals(current)) {
                        setVerifyStatus(tvVerifyRoom, "Listening for room tone...", "#888888");
                    }
                });
            }

            @Override
            public void onDetectionError(String reason) {
                runOnUiThread(() -> setVerifyStatus(
                        tvVerifyRoom,
                        "Skipped — ultrasound unavailable: " + reason,
                        "#F39C12"));
            }
        });

        ultrasoundDetector.start();
    }

    private void stopUltrasoundDetector() {
        if (ultrasoundDetector != null) {
            ultrasoundDetector.stop();
            ultrasoundDetector = null;
        }
    }

    private float normalizeUltrasoundConfidence(double magnitude) {
        double confidence = magnitude / (1500.0 * 4.0);
        confidence = Math.max(0.0, Math.min(1.0, confidence));
        return (float) confidence;
    }

    // ── IStudentView ───────────────────────────────────────────────────────

    @Override public void updateSignal(int rssi, boolean inRange) {
        // Map RSSI to 0-100 progress
        int clamped  = Math.max(RSSI_MIN, Math.min(RSSI_MAX, rssi));
        int progress = (int) (((float)(clamped - RSSI_MIN) / (RSSI_MAX - RSSI_MIN)) * 100);
        pbSignal.setProgress(progress);

        int color = inRange ? 0xFF27AE60 : 0xFFE74C3C;  // green : red
        pbSignal.setProgressTintList(ColorStateList.valueOf(color));

        tvRssi.setText(rssi + " dBm");
        tvSignalLabel.setText(inRange ? "In range — marking attendance…" : "Searching for professor…");

        // SPRINT-1-UI: Update BLE signal row in verification panel
        if (rssi >= -50) {
            setVerifyStatus(tvVerifyBle, "Strong (" + rssi + " dBm)", "#2ECC71");
        } else if (rssi >= -75) {
            setVerifyStatus(tvVerifyBle, "Moderate (" + rssi + " dBm)", "#F39C12");
        } else {
            setVerifyStatus(tvVerifyBle, "Weak (" + rssi + " dBm)", "#E74C3C");
        }
    }

    @Override public void onBeaconStarted() {
        tvStatus.setText("Status: Broadcasting your name…");
        btnScan.setText("⏹ Stop Scanning");
    }

    @Override public void onAttendanceMarked() {
        tvStatus.setText("Status: ✅ Attendance marked!");
        tvSignalLabel.setText("Attendance recorded");
        stopUltrasoundDetector();
        Toast.makeText(this, "✅ Attendance marked successfully!", Toast.LENGTH_LONG).show();
    }

    @Override public void onAlreadyMarked() {
        tvStatus.setText("Status: Already marked for this session.");
        stopUltrasoundDetector();
        Toast.makeText(this, "Already marked present for this session.", Toast.LENGTH_SHORT).show();
    }

    @Override public void showStatus(String message) {
        tvStatus.setText("Status: " + message);
    }

    @Override public void showError(String message) {
        // SPRINT-1-UI: Use Snackbar for actionable errors instead of Toast
        Snackbar.make(btnScan, message, Snackbar.LENGTH_LONG).show();
        tvStatus.setText("Status: " + message);
    }

    @Override public void setScanEnabled(boolean enabled) {
        btnScan.setEnabled(enabled);
        if (enabled) btnScan.setText("📡 Scan for Class");
        if (!enabled) return;
        if (!presenter.isScanning()) {
            stopUltrasoundDetector();
        }
    }

    @Override public void setSignalPanelVisible(boolean visible) {
        layoutSignal.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    // ── Sensor capabilities (new IStudentView methods) ────────────────────

    @Override
    public void showSensorCapabilities(boolean hasBarometer, boolean hasAudio,
                                       boolean hasUltrasound) {
        // Build a human-readable summary of what this device can and can't do
        StringBuilder sb = new StringBuilder();

        sb.append(hasBarometer
                ? "🌡️ Floor check: Ready\n"
                : "🌡️ Floor check: Skipped — no barometer sensor\n");

        sb.append(hasUltrasound
                ? "🔊 Room check: Ready\n"
                : "🔊 Room check: Skipped — mic unavailable\n");

        sb.append(hasAudio
                ? "🎵 Audio check: Ready\n"
                : "🎵 Audio check: Skipped — mic unavailable\n");

        // BLE is always available (we wouldn't reach this screen without it)
        sb.append("📡 BLE signal: Ready");

        // Count how many signals are active
        int activeCount = 1; // BLE is always active
        if (hasBarometer)  activeCount++;
        if (hasAudio)      activeCount++;
        if (hasUltrasound) activeCount++;

        if (activeCount == 4) {
            sb.insert(0, "All 4 verification signals are active.\n\n");
            // Change banner to green tint for "all good"
            bannerSensorCaps.setBackgroundColor(Color.parseColor("#E8F5E9"));
            findViewById(R.id.tv_sensor_caps_title).setVisibility(View.GONE);
        } else {
            sb.append("\n\n✅ Verification will continue with ")
              .append(activeCount)
              .append("/4 signals — the algorithm automatically increases ")
              .append("weight on active sensors to compensate.");

            // Also mark unavailable rows immediately so student isn't confused
            if (!hasBarometer) {
                setVerifyStatus(tvVerifyFloor,
                        "Skipped — no sensor, continuing with other signals",
                        "#F39C12");
            }
            if (!hasUltrasound) {
                setVerifyStatus(tvVerifyRoom,
                        "Skipped — mic unavailable",
                        "#F39C12");
            }
            if (!hasAudio) {
                setVerifyStatus(tvVerifyAudio,
                        "Skipped — mic unavailable",
                        "#F39C12");
            }
        }

        tvSensorCapsDetail.setText(sb.toString());
        bannerSensorCaps.setVisibility(View.VISIBLE);
    }

    @Override
    public void showVerificationOutcome(String status, float fusionScore, String reason) {
        String scoreText = String.format(Locale.US, "%.0f%%", fusionScore * 100);

        switch (status) {
            case "PRESENT":
                setVerifyStatus(tvVerifyOverall,
                        "✅ Verified (" + scoreText + ")", "#2ECC71");
                break;

            case "FLAGGED":
                setVerifyStatus(tvVerifyOverall,
                        "⚠️ Flagged for review (" + scoreText + ")", "#F39C12");
                break;

            case "CONFLICT":
                setVerifyStatus(tvVerifyOverall,
                        "⚠️ Signal conflict detected", "#E74C3C");
                break;

            case "REJECTED_FLOOR":
                setVerifyStatus(tvVerifyOverall,
                        "❌ Wrong floor detected", "#E74C3C");
                break;

            case "REJECTED_ROOM":
                setVerifyStatus(tvVerifyOverall,
                        "❌ Not in the room", "#E74C3C");
                break;

            case "REJECTED_SCORE":
                setVerifyStatus(tvVerifyOverall,
                        "❌ Insufficient evidence (" + scoreText + ")", "#E74C3C");
                break;

            default:
                setVerifyStatus(tvVerifyOverall,
                        status + " (" + scoreText + ")", "#888888");
                break;
        }

        // Show reason below if present
        if (reason != null && !reason.isEmpty()) {
            tvVerifyReason.setText(reason);
            tvVerifyReason.setVisibility(View.VISIBLE);
        } else {
            tvVerifyReason.setVisibility(View.GONE);
        }
    }
}
