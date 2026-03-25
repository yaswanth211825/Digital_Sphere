package com.example.digitalsphere.data.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.ParcelUuid;

/** Professor-side BLE beacon. Broadcasts SESSION_UUID so students can detect proximity. */
class BleAdvertiser {

    static final String SESSION_UUID = "0000ABCD-0000-1000-8000-00805F9B34FB";

    interface Listener {
        void onStarted();
        void onFailed(String reason);
    }

    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback     callback;

    void start(Listener listener) {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            listener.onFailed("Bluetooth is not enabled. Please turn on Bluetooth.");
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

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid.fromString(SESSION_UUID))
                .setIncludeDeviceName(false)
                .build();

        callback = new AdvertiseCallback() {
            @Override public void onStartSuccess(AdvertiseSettings s) { listener.onStarted(); }
            @Override public void onStartFailure(int errorCode) {
                listener.onFailed(advertiseError(errorCode));
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

    private String advertiseError(int code) {
        switch (code) {
            case AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED:    return "Advertising already active.";
            case AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE:     return "Advertise data too large.";
            case AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED:return "BLE advertising not supported on this device.";
            case AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:return "Too many active advertisers. Restart Bluetooth.";
            default: return "Failed to start advertising (error " + code + ").";
        }
    }
}
