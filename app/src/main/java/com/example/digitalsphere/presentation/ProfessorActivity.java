package com.example.digitalsphere.presentation;

import android.content.Context;
import android.graphics.Color;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.digitalsphere.R;
import com.example.digitalsphere.contract.IProfessorView;
import com.example.digitalsphere.data.audio.UltrasoundEmitter;
import com.example.digitalsphere.data.sensor.BarometerReader;
import com.example.digitalsphere.data.sensor.ResearchLogger;
import com.example.digitalsphere.domain.SessionManager;
import com.example.digitalsphere.presenter.ProfessorPresenter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thin view — zero business logic.
 * All decisions delegated to ProfessorPresenter.
 *
 * SPRINT-1-UI: Added BarometerReader integration for real-time
 * pressure display and BLE payload embedding.
 */
public class ProfessorActivity extends AppCompatActivity implements IProfessorView {

    private ProfessorPresenter presenter;
    private final SessionManager sessionManager = new SessionManager();

    // B-07 fix: cache session ID so onBeaconStarted() can build the full label
    // without appending to whatever text is already in the TextView.
    private String currentSessionId = "";

    // SPRINT-1-UI: Barometer integration
    private BarometerReader barometerReader;
    private float           currentPressureHPa = 0.0f;  // 0.0 = sentinel for "no reading"
    private boolean         barometerAvailable  = false;

    /**
     * @deprecated DEBUG ONLY — temporary ultrasound test emitter.
     *             Remove after hardware verification is complete.
     */
    @Deprecated
    private UltrasoundEmitter debugEmitter;

    /** Session ultrasound emitter — active while a real session is running. */
    private UltrasoundEmitter sessionEmitter;

    // Views
    private EditText    etSessionName;
    private EditText    etDuration;
    private TextView    tvSessionStatus;
    private TextView    tvCountdown;
    private TextView    tvCount;
    private Button      btnStart;
    private Button      btnStop;
    private Button      btnExport;
    private ListView    lvAttendance;
    private ArrayAdapter<String> listAdapter;
    private final List<String>   attendanceItems = new ArrayList<>();

    // SPRINT-1-UI: Sensor status card views
    private TextView    tvBaroStatus;
    private TextView    tvPressureValue;
    private TextView    tvUltrasoundStatus;
    private TextView    tvAudioStatus;
    private TextView    tvBleStatus;
    private TextView    tvSignalsSummary;

    // Sensor warning banner — shown when professor device is missing critical sensors
    private LinearLayout bannerSensorWarning;
    private TextView     tvSensorWarningText;

    // Track which sensors are available for the summary line
    private boolean micAvailable = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_professor);

        etSessionName   = findViewById(R.id.et_session_name);
        etDuration      = findViewById(R.id.et_duration);
        tvSessionStatus = findViewById(R.id.tv_session_status);
        tvCountdown     = findViewById(R.id.tv_countdown);
        tvCount         = findViewById(R.id.tv_count);
        btnStart        = findViewById(R.id.btn_start_session);
        btnStop         = findViewById(R.id.btn_stop_session);
        btnExport       = findViewById(R.id.btn_export);
        lvAttendance    = findViewById(R.id.lv_attendance);

        // SPRINT-1-UI: Sensor status card — all 4 modalities + BLE + summary
        tvBaroStatus       = findViewById(R.id.tv_baro_status);
        tvPressureValue    = findViewById(R.id.tv_pressure_value);
        tvUltrasoundStatus = findViewById(R.id.tv_ultrasound_status);
        tvAudioStatus      = findViewById(R.id.tv_audio_status);
        tvBleStatus        = findViewById(R.id.tv_ble_status);
        tvSignalsSummary   = findViewById(R.id.tv_signals_summary);

        // Sensor warning banner
        bannerSensorWarning = findViewById(R.id.banner_sensor_warning);
        tvSensorWarningText = findViewById(R.id.tv_sensor_warning_text);

        listAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, attendanceItems);
        lvAttendance.setAdapter(listAdapter);

        presenter = new ProfessorPresenter(this);
        presenter.attach(this);

        // SPRINT-1-UI: Initialize all sensors and show availability
        initSensors();

        btnStart.setOnClickListener(v -> {
            String sessionName = etSessionName.getText().toString().trim();
            String normalizedSessionId = sessionManager.createSessionId(sessionName);

            // ── Ultrasound: derive token + start emitting BEFORE BLE starts ──
            // The token must be known before we call startSession() so it can
            // be embedded in the BLE manufacturer data payload.
            int sessionToken = -1;
            if (micAvailable) {
                // Stop any previous session emitter
                if (sessionEmitter != null) {
                    sessionEmitter.stopEmitting();
                    sessionEmitter = null;
                }

                sessionEmitter = new UltrasoundEmitter(normalizedSessionId, new UltrasoundEmitter.EmitterCallback() {
                    @Override
                    public void onEmissionStarted() {
                        runOnUiThread(() -> {
                            tvUltrasoundStatus.setText("🔊 Ultrasound: ✅ Emitting (token: "
                                    + sessionEmitter.getSessionToken() + ")");
                            tvUltrasoundStatus.setTextColor(Color.parseColor("#2ECC71"));
                        });
                    }

                    @Override
                    public void onEmissionFailed(String reason) {
                        runOnUiThread(() -> {
                            tvUltrasoundStatus.setText("🔊 Ultrasound: ❌ Failed — " + reason);
                            tvUltrasoundStatus.setTextColor(Color.parseColor("#E74C3C"));
                            updateSensorSummary();
                        });
                    }
                });

                sessionToken = sessionEmitter.getSessionToken();
                sessionEmitter.startEmitting();
            }

            // SPRINT-1-UI: Pass current pressure + real ultrasound token to presenter
            presenter.startSession(
                    sessionName,
                    etDuration.getText().toString().trim(),
                    currentPressureHPa,
                    sessionToken);
        });

        btnStop.setOnClickListener(v -> presenter.stopSession());

        btnExport.setOnClickListener(v -> presenter.exportCsv());

        // DEBUG: Temporary ultrasound test — REMOVE after hardware verification
        initDebugUltrasoundButton();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // SPRINT-1-UI: Stop barometer to prevent memory leak and battery drain
        if (barometerReader != null) {
            barometerReader.stopReading();
        }
        // Stop session ultrasound emitter
        if (sessionEmitter != null) {
            sessionEmitter.stopEmitting();
            sessionEmitter = null;
        }
        // DEBUG: Stop ultrasound test if running
        if (debugEmitter != null) {
            debugEmitter.stopEmitting();
            debugEmitter = null;
        }
        presenter.detach();
    }

    // ── DEBUG: Temporary ultrasound test — REMOVE after hardware verification ──

    /**
     * @deprecated DEBUG ONLY — wires the "Test Ultrasound" button.
     *
     * Tap once  → ensures media volume is up, plays a short AUDIBLE beep to
     *             confirm the speaker works, then starts emitting at 18500 Hz.
     *             Shows real-time status text and button turns red.
     * Auto-stop → after 15 seconds, emission stops, button resets.
     * Tap while running → stops immediately.
     *
     * NOTE: The 18500 Hz tone is INAUDIBLE by design (above adult hearing range).
     * To confirm it's actually playing, use "Spectroid" (free, Play Store) on a
     * SECOND phone — you should see a peak at 18.5 kHz in the spectrogram.
     * The audible beep at the start confirms the speaker pathway is working.
     *
     * REMOVE this entire method + button + field once verified.
     */
    @Deprecated
    private void initDebugUltrasoundButton() {
        Button btnTest = findViewById(R.id.btn_test_ultrasound);
        TextView tvDebug = findViewById(R.id.tv_ultrasound_debug);
        Handler handler = new Handler(Looper.getMainLooper());

        btnTest.setOnClickListener(v -> {
            // If already running, stop immediately
            if (debugEmitter != null && debugEmitter.isEmitting()) {
                debugEmitter.stopEmitting();
                resetUltrasoundButton(btnTest, tvDebug);
                Toast.makeText(this, "Ultrasound stopped.", Toast.LENGTH_SHORT).show();
                return;
            }

            // ── Step 1: Ensure media volume is audible ──────────────────
            AudioManager audioMgr = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int currentVol = audioMgr.getStreamVolume(AudioManager.STREAM_MUSIC);
            int maxVol = audioMgr.getStreamMaxVolume(AudioManager.STREAM_MUSIC);

            if (currentVol == 0) {
                // Set to 70% of max — loud enough to emit but not blasting
                int targetVol = (int) (maxVol * 0.7);
                audioMgr.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol,
                        AudioManager.FLAG_SHOW_UI);
                tvDebug.setText("Volume was at 0 — raised to 70%. Tap again if needed.");
                tvDebug.setVisibility(android.view.View.VISIBLE);
                Toast.makeText(this,
                        "⚠️ Media volume was at 0! Raised to 70%.\nTap the button again to start.",
                        Toast.LENGTH_LONG).show();
                return;
            }

            // Show volume info
            int volPercent = (int) (100.0 * currentVol / maxVol);
            tvDebug.setText("Media volume: " + volPercent + "% — starting...");
            tvDebug.setVisibility(android.view.View.VISIBLE);

            // ── Step 2: Play a short AUDIBLE beep to confirm speaker works ──
            // This proves the audio path is active before we go ultrasonic.
            try {
                ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_MUSIC, 80);
                tg.startTone(ToneGenerator.TONE_PROP_BEEP, 300); // 300ms beep
                handler.postDelayed(tg::release, 500);
            } catch (Exception e) {
                // ToneGenerator can fail on some devices — not fatal
                tvDebug.setText("⚠️ Beep failed (" + e.getMessage() + ") — trying ultrasound anyway...");
            }

            // ── Step 3: Start ultrasonic emission after beep finishes ────
            handler.postDelayed(() -> {
                debugEmitter = new UltrasoundEmitter("test", new UltrasoundEmitter.EmitterCallback() {
                    @Override
                    public void onEmissionStarted() {
                        handler.post(() ->
                            tvDebug.setText("🔊 EMITTING at 18500 Hz (inaudible by design)\n"
                                    + "Use Spectroid app on another phone to see the peak."));
                    }

                    @Override
                    public void onEmissionFailed(String reason) {
                        handler.post(() -> {
                            tvDebug.setText("❌ FAILED: " + reason);
                            tvDebug.setTextColor(Color.parseColor("#E74C3C"));
                            resetUltrasoundButton(btnTest, tvDebug);
                            Toast.makeText(ProfessorActivity.this,
                                    "❌ Ultrasound failed: " + reason, Toast.LENGTH_LONG).show();
                        });
                    }
                });

                debugEmitter.startEmitting();

                int token = debugEmitter.getSessionToken();

                // Visual feedback: button turns red while emitting
                btnTest.setText("⏹ Stop Ultrasound");
                btnTest.setBackgroundTintList(
                        android.content.res.ColorStateList.valueOf(Color.parseColor("#E74C3C")));

                tvDebug.setText("🔊 EMITTING — Token: " + token + " at 18500 Hz\n"
                        + "OOK frame: [1][" + ((token>>3)&1) + "][" + ((token>>2)&1) + "]["
                        + ((token>>1)&1) + "][" + (token&1) + "][1]\n"
                        + "⚠️ 18.5 kHz is INAUDIBLE — use Spectroid on another phone to verify.\n"
                        + "Auto-stop in 15 seconds.");
                tvDebug.setTextColor(Color.parseColor("#9B59B6"));

                // Auto-stop after 15 seconds
                handler.postDelayed(() -> {
                    if (debugEmitter != null && debugEmitter.isEmitting()) {
                        debugEmitter.stopEmitting();
                        resetUltrasoundButton(btnTest, tvDebug);
                        tvDebug.setText("Auto-stopped after 15s. Tap to test again.");
                        tvDebug.setVisibility(android.view.View.VISIBLE);
                    }
                }, 15_000);

            }, 500); // 500ms delay to let the audible beep finish
        });
    }

    /** Resets the ultrasound debug button to its default state. */
    @Deprecated
    private void resetUltrasoundButton(Button btnTest, TextView tvDebug) {
        btnTest.setText("🔊 Test Ultrasound");
        btnTest.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(Color.parseColor("#9B59B6")));
        // Don't hide tvDebug — keep last status message visible
    }

    // ── Sensor initialisation — checks ALL 4 modalities ─────────────────────

    /**
     * Checks every sensor this device can provide and updates the sensor
     * status card. Any sensor that's missing shows a clear warning but
     * NEVER blocks — the session always proceeds with whatever is available.
     */
    private void initSensors() {
        // ── 1. Barometer ────────────────────────────────────────────────
        initBarometer();

        // ── 2. Microphone (needed by both ultrasound and audio) ─────────
        micAvailable = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;

        if (micAvailable) {
            tvUltrasoundStatus.setText("🔊 Ultrasound: ✅ Ready (emitter on this device)");
            tvUltrasoundStatus.setTextColor(Color.parseColor("#2ECC71"));

            tvAudioStatus.setText("🎵 Audio: ✅ Ready (mic available)");
            tvAudioStatus.setTextColor(Color.parseColor("#2ECC71"));
        } else {
            tvUltrasoundStatus.setText("🔊 Ultrasound: ⚠️ Mic permission not granted — will skip");
            tvUltrasoundStatus.setTextColor(Color.parseColor("#F39C12"));

            tvAudioStatus.setText("🎵 Audio: ⚠️ Mic permission not granted — will skip");
            tvAudioStatus.setTextColor(Color.parseColor("#F39C12"));
        }

        // ── 3. BLE — always available (we wouldn't be here without it) ──
        // BLE status is set when beacon starts (onBeaconStarted)

        // ── 4. Build the summary + warnings ─────────────────────────────
        updateSensorSummary();
    }

    /**
     * Instantiates BarometerReader with a callback that:
     * - Updates the sensor status card in real time
     * - Caches the latest pressure for BLE payload
     * - Logs every reading for research data collection
     */
    private void initBarometer() {
        barometerReader = new BarometerReader(this, new BarometerReader.BarometerCallback() {
            @Override
            public void onPressureReading(float hPa) {
                currentPressureHPa = hPa;
                barometerAvailable = true;

                String formatted = String.format(Locale.US, "%.1f", hPa);
                tvBaroStatus.setText("🌡️ Barometer: ✅ Active — " + formatted + " hPa");
                tvBaroStatus.setTextColor(Color.parseColor("#2ECC71"));
                tvPressureValue.setText("📍 Current pressure: " + formatted + " hPa");
                tvPressureValue.setTextColor(Color.parseColor("#1A1A2E"));

                // Update summary now that we know baro is available
                updateSensorSummary();

                ResearchLogger.logPressureReading("PROFESSOR", hPa);
            }

            @Override
            public void onBarometerUnavailable() {
                barometerAvailable  = false;
                currentPressureHPa  = 0.0f;

                tvBaroStatus.setText("🌡️ Barometer: ❌ Not available — floor check will be skipped");
                tvBaroStatus.setTextColor(Color.parseColor("#E74C3C"));
                tvPressureValue.setText("📍 Continuing without floor detection. Other signals compensate.");
                tvPressureValue.setTextColor(Color.parseColor("#F39C12"));

                // Update summary + show warning banner
                updateSensorSummary();

                ResearchLogger.logBarometerAvailability(false, "PROFESSOR");
                ResearchLogger.logError("BARO_UNAVAILABLE",
                        "Professor device has no barometer — pressure will be 0.0 in BLE payload");
            }
        });

        // Show warming-up state while waiting for first reading
        tvBaroStatus.setText("🌡️ Barometer: ⚠️ Warming up...");
        tvBaroStatus.setTextColor(Color.parseColor("#F39C12"));

        if (barometerReader.isAvailable()) {
            ResearchLogger.logBarometerAvailability(true, "PROFESSOR");
            barometerReader.startReading();
        } else {
            barometerReader.startReading();  // triggers onBarometerUnavailable()
        }
    }

    /**
     * Recalculates the "N/4 signals active" summary line and shows/hides
     * the warning banner based on which sensors are missing.
     * Called after each sensor's availability is determined.
     */
    private void updateSensorSummary() {
        int active = 1; // BLE is always active
        if (barometerAvailable) active++;
        if (micAvailable)       active += 2; // ultrasound + audio both need mic

        StringBuilder warnings = new StringBuilder();

        if (!barometerAvailable) {
            warnings.append("• Floor check (barometer) is unavailable on this device.\n");
        }
        if (!micAvailable) {
            warnings.append("• Ultrasound and audio checks require RECORD_AUDIO permission.\n");
        }

        if (active == 4) {
            tvSignalsSummary.setText("✅ All 4 signals active — full verification strength");
            tvSignalsSummary.setTextColor(Color.parseColor("#2ECC71"));
            showSensorWarning(null);  // hide banner
        } else {
            tvSignalsSummary.setText("⚠️ " + active + "/4 signals active — session will proceed with reduced accuracy");
            tvSignalsSummary.setTextColor(Color.parseColor("#F39C12"));

            warnings.append("\nThe session can still run. The DSVF algorithm redistributes ")
                     .append("weight to the ")
                     .append(active)
                     .append(" active signal(s) automatically.");

            showSensorWarning(warnings.toString().trim());
        }
    }

    // ── IProfessorView ─────────────────────────────────────────────────────

    @Override public void showSessionId(String sessionId) {
        // B-07 fix: store so onBeaconStarted() can compose the full label
        currentSessionId = sessionId;
        tvSessionStatus.setText("Session ID: " + sessionId);
    }

    @Override public void updateTimer(String timeLeft) {
        tvCountdown.setText(timeLeft);
    }

    @Override public void onSessionExpired() {
        Toast.makeText(this, "Session time is up! Attendance closed.", Toast.LENGTH_LONG).show();
    }

    @Override public void onBeaconStarted() {
        // B-07 fix: compose from cached ID instead of appending to current text.
        tvSessionStatus.setText("Session ID: " + currentSessionId + "  •  Broadcasting ●");

        // SPRINT-1-UI: Update BLE status in sensor card
        tvBleStatus.setText("📡 BLE: ✅ Broadcasting");
        tvBleStatus.setTextColor(Color.parseColor("#2ECC71"));
    }

    @Override public void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override public void updateAttendanceList(List<String> entries) {
        attendanceItems.clear();
        attendanceItems.addAll(entries);
        listAdapter.notifyDataSetChanged();
    }

    @Override public void updateAttendanceCount(int count) {
        tvCount.setText(count + " present");
    }

    @Override public void setLoading(boolean loading) {
        btnStart.setText(loading ? "Starting…" : "▶ Start");
    }

    @Override public void setStartEnabled(boolean enabled) {
        btnStart.setEnabled(enabled);
    }

    @Override public void setStopEnabled(boolean enabled) {
        btnStop.setEnabled(enabled);
    }

    /** B-06 fix: reset session label and timer on stop so stale text doesn't persist. */
    @Override public void onSessionStopped() {
        currentSessionId = "";
        tvSessionStatus.setText("Session: Inactive");
        tvCountdown.setText("");

        // Stop session ultrasound emitter when session ends
        if (sessionEmitter != null) {
            sessionEmitter.stopEmitting();
            sessionEmitter = null;

            // Reset ultrasound status to Ready (if mic is available) or its previous state
            if (micAvailable) {
                tvUltrasoundStatus.setText("🔊 Ultrasound: ✅ Ready (emitter on this device)");
                tvUltrasoundStatus.setTextColor(Color.parseColor("#2ECC71"));
            }
        }

        // SPRINT-1-UI: Reset BLE status in sensor card
        tvBleStatus.setText("📡 BLE: Idle");
        tvBleStatus.setTextColor(Color.parseColor("#888888"));
    }

    // ── Sensor warning banner ────────────────────────────────────────────

    @Override
    public void showSensorWarning(String message) {
        if (message == null || message.isEmpty()) {
            bannerSensorWarning.setVisibility(android.view.View.GONE);
        } else {
            tvSensorWarningText.setText(message);
            bannerSensorWarning.setVisibility(android.view.View.VISIBLE);
        }
    }
}
