package com.example.digitalsphere.presentation;

import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.example.digitalsphere.R;
import com.example.digitalsphere.contract.IProfessorView;
import com.example.digitalsphere.presenter.ProfessorPresenter;
import java.util.ArrayList;
import java.util.List;

/**
 * Thin view — zero business logic.
 * All decisions delegated to ProfessorPresenter.
 */
public class ProfessorActivity extends AppCompatActivity implements IProfessorView {

    private ProfessorPresenter presenter;

    // B-07 fix: cache session ID so onBeaconStarted() can build the full label
    // without appending to whatever text is already in the TextView.
    private String currentSessionId = "";

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

        listAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, attendanceItems);
        lvAttendance.setAdapter(listAdapter);

        presenter = new ProfessorPresenter(this);
        presenter.attach(this);

        btnStart.setOnClickListener(v -> presenter.startSession(
                etSessionName.getText().toString().trim(),
                etDuration.getText().toString().trim()));

        btnStop.setOnClickListener(v -> presenter.stopSession());

        btnExport.setOnClickListener(v -> presenter.exportCsv());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        presenter.detach();
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
        // Note: stopSession() is called by the presenter immediately after, which
        // calls onSessionStopped() to fully reset the UI.
    }

    @Override public void onBeaconStarted() {
        // B-07 fix: compose from cached ID instead of appending to current text.
        // Old code: tvSessionStatus.getText() + "  •  Broadcasting"
        // → would keep appending if called more than once.
        tvSessionStatus.setText("Session ID: " + currentSessionId + "  •  Broadcasting ●");
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
    }
}
