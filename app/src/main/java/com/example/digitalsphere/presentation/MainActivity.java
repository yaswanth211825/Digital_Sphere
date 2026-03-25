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

    private static final int RC_PERMISSIONS = 100;

    private Intent pendingIntent;

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

        if (!hasAllPermissions()) requestNeededPermissions();
    }

    // ── Navigation guard ───────────────────────────────────────────────────

    private void checkAndNavigate() {
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            showFatal("BLE Not Supported",
                    "This device does not support Bluetooth Low Energy, " +
                    "which is required for Digital Sphere.");
            return;
        }
        if (!hasAllPermissions()) {
            requestNeededPermissions();
            return;
        }
        BluetoothAdapter bt = BluetoothAdapter.getDefaultAdapter();
        if (bt == null || !bt.isEnabled()) {
            enableBtLauncher.launch(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE));
            return;
        }
        startActivity(pendingIntent);
        pendingIntent = null;
    }

    // ── Permission helpers ─────────────────────────────────────────────────

    private boolean hasAllPermissions() {
        return neededPermissions().isEmpty();
    }

    private List<String> neededPermissions() {
        // B-09: removed BLUETOOTH / BLUETOOTH_ADMIN — deprecated & ineffective on API 31+.
        // On API 26-30 BLE access is gated by ACCESS_FINE_LOCATION (already included).
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

    private void requestNeededPermissions() {
        ActivityCompat.requestPermissions(this,
                neededPermissions().toArray(new String[0]), RC_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != RC_PERMISSIONS) return;

        for (int i = 0; i < results.length; i++) {
            if (results[i] != PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this)
                        .setTitle("Permission Required")
                        .setMessage("\"" + readable(perms[i]) + "\" is required for BLE attendance.\n\n" +
                                "Please grant it in Settings.")
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
        Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show();
        if (pendingIntent != null) checkAndNavigate();
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
