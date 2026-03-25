package com.example.digitalsphere.data.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import android.util.SparseArray;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * Professor-side scanner. Listens for student name beacons (STUDENT_UUID)
 * and extracts the student name from the BLE manufacturer data payload.
 *
 * B-10 fix: switched from CALLBACK_TYPE_FIRST_MATCH (requires hardware BLE
 * filtering — absent on many chipsets → SCAN_FAILED_FEATURE_UNSUPPORTED) to
 * CALLBACK_TYPE_ALL_MATCHES which works on every Android BLE device.
 * Deduplication is handled at the DB layer via CONFLICT_IGNORE, so repeated
 * callbacks for the same student are harmless.
 */
class ProfessorScanner {

    interface Listener {
        void onStudentDetected(String studentName);
        void onError(String reason);
    }

    private BluetoothLeScanner scanner;
    private ScanCallback        scanCallback;
    private final Listener      listener;

    ProfessorScanner(Listener listener) { this.listener = listener; }

    void start() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onError("Bluetooth is not enabled.");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onError("BLE scanner unavailable.");
            return;
        }

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(StudentBeacon.STUDENT_UUID))
                .build();

        // B-10 fix: CALLBACK_TYPE_ALL_MATCHES — universally supported.
        // FIRST_MATCH requires on-chip BLE hardware filtering; on unsupported
        // devices it silently fires SCAN_FAILED_FEATURE_UNSUPPORTED (error 4)
        // which killed the scanner before any student could be detected.
        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
                .build();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                String name = extractName(result);
                if (name != null && !name.isEmpty()) {
                    listener.onStudentDetected(name);
                }
            }
            @Override
            public void onScanFailed(int errorCode) {
                if (errorCode == SCAN_FAILED_ALREADY_STARTED) return;
                listener.onError("Student scanner failed (error " + errorCode + ").");
            }
        };

        try {
            scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
        } catch (IllegalArgumentException e) {
            listener.onError("BLE scan could not start: " + e.getMessage());
        }
    }

    void stop() {
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); }
            catch (IllegalStateException ignored) {}
            scanCallback = null;
        }
    }

    private String extractName(ScanResult result) {
        if (result.getScanRecord() == null) return null;
        SparseArray<byte[]> mfData = result.getScanRecord().getManufacturerSpecificData();
        if (mfData == null || mfData.size() == 0) return null;
        byte[] nameBytes = mfData.get(0xFFFF);
        if (nameBytes == null || nameBytes.length == 0) return null;
        return new String(nameBytes, StandardCharsets.UTF_8).trim();
    }
}
