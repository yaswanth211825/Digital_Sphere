package com.example.digitalsphere.presentation;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.example.digitalsphere.R;
import java.util.ArrayList;
import java.util.List;

/**
 * Entry point — only responsibilities:
 *   1. Request runtime permissions (FR-01)
 *   2. Prompt Bluetooth enable (FR-02)
 *   3. Route to ProfessorActivity or StudentActivity
 *
 * No business logic here.
 *
 * B-08 fix: replaced deprecated startActivityForResult / onActivityResult
 *           with ActivityResultLauncher (Activity Result API).
 * B-09 fix: removed deprecated BLUETOOTH / BLUETOOTH_ADMIN from permission check
 *           (they have no effect on API 31+ and are not needed below 31 either,
 *            since BLE is governed by ACCESS_FINE_LOCATION on API 26-30).
 */
public class MainActivity extends AppCompatActivity {

    /** Request code for mandatory BLE permissions. */
    private static final int RC_PERMISSIONS = 100;

    /** Request code for optional sensor permissions (RECORD_AUDIO). */
    private static final int RC_OPTIONAL    = 101;

    private Intent pendingIntent;

    /** Tracks whether we've already asked for optional permissions this session. */
    private boolean optionalPermissionsRequested = false;

    /**
     * B-08: Modern Activity Result API replaces startActivityForResult + onActivityResult.
     * Must be registered before onCreate() completes.
     */
    private ActivityResultLauncher<Intent> enableBtLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Register launcher in onCreate (required before first use)
        enableBtLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && pendingIntent != null) {
                        startActivity(pendingIntent);
                        pendingIntent = null;
                    } else {
                        Toast.makeText(this,
                                "Bluetooth must be enabled to use Digital Sphere.",
                                Toast.LENGTH_LONG).show();
                    }
                });

        Button btnProfessor = findViewById(R.id.btn_professor);
        Button btnStudent   = findViewById(R.id.btn_student);

        btnProfessor.setOnClickListener(v -> {
            pendingIntent = new Intent(this, ProfessorActivity.class);
            checkAndNavigate();
        });
        btnStudent.setOnClickListener(v -> {
            pendingIntent = new Intent(this, StudentActivity.class);
            checkAndNavigate();
        });

        // Request mandatory (BLE) permissions on launch
        if (!hasMandatoryPermissions()) requestMandatoryPermissions();
    }

    // ── Navigation guard ───────────────────────────────────────────────────

    private void checkAndNavigate() {
        // 1. Hardware check — BLE is non-negotiable
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            showFatal("BLE Not Supported",
                    "This device does not support Bluetooth Low Energy, " +
                    "which is required for Digital Sphere.");
            return;
        }

        // 2. Mandatory permissions (BLE) — must be granted to proceed
        if (!hasMandatoryPermissions()) {
            requestMandatoryPermissions();
            return;
        }

        // 3. Optional permissions (RECORD_AUDIO) — ask once, but don't block if denied
        if (!optionalPermissionsRequested && !hasOptionalPermissions()) {
            requestOptionalPermissions();
            return;
        }

        // 4. Bluetooth must be enabled
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null || !bt.isEnabled()) {
            enableBtLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }

        // All checks passed — navigate
        startActivity(pendingIntent);
        pendingIntent = null;
    }

    // ── Permission helpers ─────────────────────────────────────────────────

    // ── Mandatory: BLE permissions (app cannot function without these) ───

    private boolean hasMandatoryPermissions() {
        return missingMandatoryPermissions().isEmpty();
    }

    /**
     * BLE permissions that are required for the app to function at all.
     * B-09: removed deprecated BLUETOOTH / BLUETOOTH_ADMIN.
     */
    private List<String> missingMandatoryPermissions() {
        String[] all = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                ? new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION}
                : new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION};
        List<String> missing = new ArrayList<>();
        for (String p : all) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                missing.add(p);
            }
        }
        return missing;
    }

    private void requestMandatoryPermissions() {
        ActivityCompat.requestPermissions(this,
                missingMandatoryPermissions().toArray(new String[0]), RC_PERMISSIONS);
    }

    // ── Optional: RECORD_AUDIO (ultrasound + audio verification) ────────

    private boolean hasOptionalPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Requests RECORD_AUDIO with an educational rationale dialog.
     * If the user denies, the app continues with BLE-only verification
     * (ultrasound and audio checks are skipped but verification still works).
     */
    private void requestOptionalPermissions() {
        // Show rationale before requesting — explains WHY we need the mic
        new AlertDialog.Builder(this)
                .setTitle("Microphone Access")
                .setMessage("Digital Sphere uses your microphone for two verification checks:\n\n"
                        + "🔊 Ultrasound — detects an inaudible tone from the professor's phone "
                        + "to confirm you're in the same room.\n\n"
                        + "🎵 Audio — compares ambient sound to confirm matching environments.\n\n"
                        + "Without mic access, verification will still work using BLE signal "
                        + "and barometer, but with reduced accuracy.\n\n"
                        + "You can change this later in Settings.")
                .setPositiveButton("Allow", (d, w) -> {
                    ActivityCompat.requestPermissions(this,
                            new String[]{Manifest.permission.RECORD_AUDIO}, RC_OPTIONAL);
                })
                .setNegativeButton("Skip", (d, w) -> {
                    optionalPermissionsRequested = true;
                    Toast.makeText(this,
                            "Mic skipped — verification will use BLE + barometer only.",
                            Toast.LENGTH_LONG).show();
                    // Continue navigation without mic
                    if (pendingIntent != null) checkAndNavigate();
                })
                .setCancelable(false)
                .show();
    }

    // ── Permission result handling ──────────────────────────────────────

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);

        if (code == RC_PERMISSIONS) {
            // Mandatory BLE permissions
            for (int i = 0; i < results.length; i++) {
                if (results[i] != PackageManager.PERMISSION_GRANTED) {
                    new AlertDialog.Builder(this)
                            .setTitle("Permission Required")
                            .setMessage("\"" + readable(perms[i]) + "\" is required for BLE attendance.\n\n"
                                    + "Please grant it in Settings.")
                            .setPositiveButton("Open Settings", (d, w) -> {
                                Intent i2 = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                i2.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(i2);
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                    return;
                }
            }
            Toast.makeText(this, "BLE permissions granted", Toast.LENGTH_SHORT).show();
            if (pendingIntent != null) checkAndNavigate();

        } else if (code == RC_OPTIONAL) {
            // Optional RECORD_AUDIO permission
            optionalPermissionsRequested = true;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "Microphone access granted — full verification enabled.",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "Mic denied — verification will continue with BLE + barometer.",
                        Toast.LENGTH_LONG).show();
            }
            // Continue navigation regardless of mic result
            if (pendingIntent != null) checkAndNavigate();
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private void showFatal(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title).setMessage(message)
                .setPositiveButton("OK", null).show();
    }

    private static String readable(String permission) {
        String[] parts = permission.split("\\.");
        return parts[parts.length - 1].replace("_", " ");
    }
}
