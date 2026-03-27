package com.example.digitalsphere.data.ble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.os.ParcelUuid;
import java.nio.ByteBuffer;                     // NEW — for packing/unpacking
import java.nio.ByteOrder;                      // NEW — for consistent endianness

/**
 * Professor-side BLE beacon. Broadcasts SESSION_UUID so students can detect
 * proximity, plus manufacturer data carrying barometric pressure and a
 * session token for the ultrasound verification layer.
 *
 * Packet layout (31 bytes total):
 *   Flags .............. 3 bytes
 *   16-bit UUID list ... 4 bytes  (SESSION_UUID → 0xABCD)
 *   Manufacturer header  4 bytes  (length + type + company ID 0xFFFF)
 *   Manufacturer data .. 4 bytes  ← NEW: [pressure 2B][token 2B]
 *                       ────────
 *                        15 bytes used → 16 bytes spare (well within budget)
 *
 * Pressure encoding:
 *   (short)(hPa × 10), stored as unsigned 16-bit big-endian.
 *   Range: 0–6553.5 hPa — covers every terrestrial pressure (typical ~1013 hPa).
 *   Resolution: 0.1 hPa — well under the 0.30 hPa same-floor threshold.
 *   Example: 1013.25 hPa → (int)(1013.25 × 10) = 10132 → 0x27_94 big-endian.
 *   Decode: (0x2794 & 0xFFFF) / 10.0 = 1013.2 hPa.
 *
 * Token encoding:
 *   Raw 16-bit unsigned integer, big-endian. Interpretation is up to the
 *   ultrasound/verification layer.
 */
class BleAdvertiser {

    static final String SESSION_UUID = "0000ABCD-0000-1000-8000-00805F9B34FB";

    // NEW — company ID matches StudentBeacon so both sides use the same key
    //        when reading manufacturer data from ScanRecord.
    static final int COMPANY_ID = 0xFFFF;

    // NEW — byte offsets within the manufacturer data payload
    private static final int OFFSET_PRESSURE = 0;   // bytes 0-1
    private static final int OFFSET_TOKEN    = 2;   // bytes 2-3
    static final int HEADER_SIZE             = 4;    // total header bytes

    interface Listener {
        void onStarted();
        void onFailed(String reason);
    }

    private BluetoothLeAdvertiser advertiser;
    private AdvertiseCallback     callback;

    // MODIFIED — now accepts pressure and token to embed in the advertisement.
    /**
     * Starts the professor beacon with sensor data embedded in manufacturer data.
     *
     * @param pressureHPa current barometric pressure in hPa (e.g. 1013.25).
     *                    Pass 0.0f if barometer is unavailable — the student
     *                    side will treat 0 as "pressure not provided".
     * @param sessionToken 4-bit token for the ultrasound layer. Pass -1 if
     *                     ultrasound is not yet active.
     * @param listener     lifecycle callbacks.
     */
    void start(float pressureHPa, int sessionToken, Listener listener) {
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

        // NEW — pack pressure + token into 4 bytes of manufacturer data
        byte[] payload = packPayload(pressureHPa, sessionToken);

        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .setConnectable(false)
                .build();

        AdvertiseData data = new AdvertiseData.Builder()
                .addServiceUuid(ParcelUuid.fromString(SESSION_UUID))
                .addManufacturerData(COMPANY_ID, payload)               // MODIFIED — was no manufacturer data
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

    // ── NEW — Static pack / unpack helpers ────────────────────────────────
    //
    //  These are static so ProfessorScanner (professor side) and
    //  BleScanner (student side) can decode the payload without
    //  instantiating a BleAdvertiser.

    /**
     * Packs pressure and session token into a 4-byte big-endian array.
     *
     * Pressure is stored as an unsigned 16-bit value = (int)(hPa × 10).
     * This gives 0.1 hPa resolution — well under the 0.30 hPa same-floor
     * threshold — and supports up to 6553.5 hPa (terrestrial max ~1084 hPa).
     *
     * @param pressureHPa barometric pressure (e.g. 1013.25). Clamped to
     *                    [0, 6553.5] which covers every terrestrial reading.
     * @param token       16-bit session token. Masked to 0xFFFF.
     * @return 4-byte array: [pressure_hi, pressure_lo, token_hi, token_lo].
     */
    static byte[] packPayload(float pressureHPa, int token) {
        // Clamp to unsigned 16-bit range: 0 .. 65535 → 0.0 .. 6553.5 hPa
        int pressureEncoded = Math.max(0, Math.min(65535, (int) (pressureHPa * 10)));
        int tokenEncoded = token >= 0 ? ((token & 0xFFFF) + 1) : 0;

        return ByteBuffer.allocate(HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
                .putShort((short) pressureEncoded)
                .putShort((short) tokenEncoded)
                .array();
    }

    /**
     * Extracts the barometric pressure from a manufacturer data payload.
     *
     * Call this on the byte[] obtained from
     * {@code scanRecord.getManufacturerSpecificData(COMPANY_ID)} on the
     * student side (BleScanner) to read the professor's pressure.
     *
     * @param data raw manufacturer data bytes (at least 2 bytes).
     * @return pressure in hPa (e.g. 1013.25), or 0.0f if data is null/short.
     */
    static float unpackPressure(byte[] data) {
        if (data == null || data.length < OFFSET_PRESSURE + 2) return 0.0f;

        // Read 2 bytes as unsigned short → divide by 10 to get hPa
        int raw = ByteBuffer.wrap(data, OFFSET_PRESSURE, 2)
                .order(ByteOrder.BIG_ENDIAN)
                .getShort() & 0xFFFF;
        return raw / 10.0f;
    }

    /**
     * Extracts the session token from a manufacturer data payload.
     *
     * @param data raw manufacturer data bytes (at least 4 bytes).
     * @return decoded 4-bit token, or -1 if ultrasound is inactive / unavailable.
     */
    static int unpackToken(byte[] data) {
        if (data == null || data.length < OFFSET_TOKEN + 2) return -1;

        int raw = ByteBuffer.wrap(data, OFFSET_TOKEN, 2)
                .order(ByteOrder.BIG_ENDIAN)
                .getShort() & 0xFFFF;
        return raw == 0 ? -1 : raw - 1;
    }

    // ── Unchanged ─────────────────────────────────────────────────────────

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
