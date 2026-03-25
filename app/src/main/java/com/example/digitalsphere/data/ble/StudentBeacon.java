package com.example.digitalsphere.data.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.ParcelUuid;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Student-side BLE beacon. Broadcasts the student's name so the professor's
 * phone can receive it — no internet, pure BLE.
 *
 * Payload budget: 31 bytes total
 *   Flags (3) + 16-bit UUID list (4) + manufacturer header (4) = 11 bytes overhead
 *   → 20 bytes available for student name (FR-45)
 */
class StudentBeacon {

    static final String STUDENT_UUID   = "0000DCBA-0000-1000-8000-00805F9B34FB";
    private static final int COMPANY_ID      = 0xFFFF;
    private static final int MAX_NAME_BYTES  = 20;

    interface Listener {
        void onStarted();
        void onFailed(String reason);
    }

    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback     callback;

    void start(String studentName, Listener listener) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onFailed("Bluetooth is not enabled.");
            return;
        }
        if (!adapter.isMultipleAdvertisementSupported()) {
            listener.onFailed("This device does not support BLE advertising.");
            return;
        }
        advertiser = adapter.getBluetoothLeAdvertiser();
        if (advertiser == null) {
            listener.onFailed("BLE advertiser unavailable. Please restart Bluetooth.");
            return;
        }

        byte[] nameBytes = studentName.getBytes(StandardCharsets.UTF_8);
        if (nameBytes.length > MAX_NAME_BYTES) {
            nameBytes = Arrays.copyOf(nameBytes, MAX_NAME_BYTES);
        }

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .setTimeout(0)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid.fromString(STUDENT_UUID))
                .addManufacturerData(COMPANY_ID, nameBytes)
                .setIncludeDeviceName(false)
                .build();

        callback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings s) { listener.onStarted(); }
            @Override public void onStartFailure(int errorCode) {
                if (errorCode == ADVERTISE_FAILED_ALREADY_STARTED) {
                    listener.onStarted(); // already broadcasting — treat as success
                    return;
                }
                listener.onFailed(beaconError(errorCode));
            }
        };

        advertiser.startAdvertising(settings, data, callback);
    }

    void stop() {
        if (advertiser != null && callback != null) {
            try { advertiser.stopAdvertising(callback); }
            catch (IllegalStateException ignored) {}
            callback = null;
        }
    }

    private String beaconError(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:      return "Name too long to broadcast. Please shorten your name.";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED: return "BLE advertising not supported on this device.";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:return "Too many BLE advertisers. Restart Bluetooth.";
            default: return "Failed to broadcast name (error " + code + ").";
        }
    }
}
