package com.example.digitalsphere.data.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.os.ParcelUuid;
import java.util.Collections;

/** Student-side proximity scanner. Detects professor's SESSION_UUID beacon and reports RSSI. */
class BleScanner {

    static final int RSSI_THRESHOLD = -75;

    interface Listener {
        void onResult(int rssi, boolean inRange);
        void onProfessorMetadata(float pressureHPa, int sessionToken, float[] ambientHash);
        void onError(String reason);
    }

    private BluetoothLeScanner scanner;
    private ScanCallback        scanCallback;
    private final Listener      listener;

    BleScanner(Listener listener) { this.listener = listener; }

    void start() {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onError("Bluetooth is not enabled. Please turn on Bluetooth.");
            return;
        }
        scanner = adapter.getBluetoothLeScanner();
        if (scanner == null) {
            listener.onError("BLE scanner unavailable. This device may not support BLE scanning.");
            return;
        }

        ScanFilter filter = new ScanFilter.Builder()
                .setServiceUuid(ParcelUuid.fromString(BleAdvertiser.SESSION_UUID))
                .build();

        ScanSettings settings = new ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build();

        scanCallback = new ScanCallback() {
            @Override
            public void onScanResult(int callbackType, ScanResult result) {
                int rssi = result.getRssi();
                byte[] payload = null;
                if (result.getScanRecord() != null) {
                    payload = result.getScanRecord()
                            .getManufacturerSpecificData(BleAdvertiser.COMPANY_ID);
                }
                listener.onProfessorMetadata(
                        BleAdvertiser.unpackPressure(payload),
                        BleAdvertiser.unpackToken(payload),
                        BleAdvertiser.unpackAmbientHash(payload));
                listener.onResult(rssi, rssi >= RSSI_THRESHOLD);
            }
            @Override
            public void onScanFailed(int errorCode) {
                listener.onError(scanError(errorCode));
            }
        };

        scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
    }

    void stop() {
        if (scanner != null && scanCallback != null) {
            try { scanner.stopScan(scanCallback); }
            catch (IllegalStateException ignored) {}
            scanCallback = null;
        }
    }

    private String scanError(int code) {
        switch (code) {
            case ScanCallback.SCAN_FAILED_ALREADY_STARTED:               return "Scan already in progress.";
            case ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED: return "Failed to register scanner. Restart the app.";
            case ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED:           return "BLE scanning not supported on this device.";
            default: return "Scan failed (error " + code + "). Please try again.";
        }
    }
}
