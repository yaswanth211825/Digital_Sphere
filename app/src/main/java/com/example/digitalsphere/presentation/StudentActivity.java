package com.example.digitalsphere.presentation;

import android.content.res.ColorStateList;
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
import com.example.digitalsphere.presenter.StudentPresenter;

/**
 * Thin view — zero business logic.
 * All decisions delegated to StudentPresenter.
 */
public class StudentActivity extends AppCompatActivity implements IStudentView {

    private static final int RSSI_MIN = -100;
    private static final int RSSI_MAX =  -30;

    private StudentPresenter presenter;

    // Views
    private EditText     etName;
    private EditText     etSessionId;
    private LinearLayout layoutSignal;
    private TextView     tvSignalLabel;
    private ProgressBar  pbSignal;
    private TextView     tvRssi;
    private TextView     tvStatus;
    private Button       btnScan;

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

        presenter = new StudentPresenter(this);
        presenter.attach(this);

        btnScan.setOnClickListener(v -> {
            if (presenter.isScanning()) {
                presenter.stopScan();
                btnScan.setText("📡 Scan for Class");
            } else {
                presenter.startScan(
                        etName.getText().toString().trim(),
                        etSessionId.getText().toString().trim());
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        presenter.detach();
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
    }

    @Override public void onBeaconStarted() {
        tvStatus.setText("Status: Broadcasting your name…");
        btnScan.setText("⏹ Stop Scanning");
    }

    @Override public void onAttendanceMarked() {
        tvStatus.setText("Status: ✅ Attendance marked!");
        tvSignalLabel.setText("Attendance recorded");
        Toast.makeText(this, "✅ Attendance marked successfully!", Toast.LENGTH_LONG).show();
    }

    @Override public void onAlreadyMarked() {
        tvStatus.setText("Status: Already marked for this session.");
        Toast.makeText(this, "Already marked present for this session.", Toast.LENGTH_SHORT).show();
    }

    @Override public void showStatus(String message) {
        tvStatus.setText("Status: " + message);
    }

    @Override public void showError(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        tvStatus.setText("Status: " + message);
    }

    @Override public void setScanEnabled(boolean enabled) {
        btnScan.setEnabled(enabled);
        if (enabled) btnScan.setText("📡 Scan for Class");
    }

    @Override public void setSignalPanelVisible(boolean visible) {
        layoutSignal.setVisibility(visible ? View.VISIBLE : View.GONE);
    }
}
