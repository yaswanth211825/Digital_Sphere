package com.example.digitalsphere.data.sensor;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Reads the device barometer (TYPE_PRESSURE) to detect ambient air pressure.
 * Used to verify a student is physically on the same floor as the professor
 * by comparing pressure readings exchanged over the BLE/session channel.
 *
 * This is a pure data-layer class — no UI, no logging, no business logic.
 * The presenter/verification layer decides what to do with the readings.
 *
 * Thread safety: {@link #startReading()} and {@link #stopReading()} are
 * synchronized so they can be called safely from any thread (main thread,
 * BLE callback thread, or background timer thread).
 */
public class BarometerReader {

    /**
     * Threshold for "same floor" determination.
     * 0.30 hPa ≈ 2.5 metres altitude difference — covers normal room-height
     * variance between two phone sensors on the same floor, but rejects
     * readings from a different storey (~1.2 hPa per floor).
     */
    private static final float SAME_FLOOR_THRESHOLD_HPA = 0.30f;

    /**
     * Approximate metres of altitude change per 1 hPa of pressure difference,
     * derived from the barometric formula at sea-level standard conditions.
     * Good enough for relative floor-to-floor comparison; not for GPS altitude.
     */
    private static final float METRES_PER_HPA = 8.3f;

    // ── Callback interface ────────────────────────────────────────────────

    /**
     * Listener for barometer events. The verification layer registers this
     * so it receives raw hPa values without coupling to SensorManager.
     */
    public interface BarometerCallback {

        /**
         * Called each time the barometer delivers a new pressure sample.
         *
         * @param hPa atmospheric pressure in hectopascals (millibars).
         *            Typical sea-level value ≈ 1013.25 hPa.
         */
        void onPressureReading(float hPa);

        /**
         * Called exactly once if the device has no TYPE_PRESSURE sensor.
         * The verification layer should fall back to other signals (BLE RSSI,
         * ultrasound) when this fires — barometer cannot contribute.
         */
        void onBarometerUnavailable();
    }

    // ── Fields ────────────────────────────────────────────────────────────

    private final SensorManager    sensorManager;
    private final Sensor           pressureSensor;   // null when hardware absent
    private final BarometerCallback callback;

    /** Guard for start/stop to prevent double-register or unregister-before-register. */
    private final Object lock = new Object();
    private boolean      listening = false;

    /**
     * Stateless listener — just forwards the raw hPa value to the callback.
     * Kept as a field so the exact same instance is passed to both
     * registerListener() and unregisterListener().
     */
    private final SensorEventListener sensorListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.sensor.getType() == Sensor.TYPE_PRESSURE) {
                callback.onPressureReading(event.values[0]);
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            // Not relevant for attendance verification — accuracy changes
            // on barometer are rare and don't affect floor-level precision.
        }
    };

    // ── Constructor ───────────────────────────────────────────────────────

    /**
     * @param context  any Context — only used to obtain the system SensorManager.
     *                 The application context is extracted internally to avoid
     *                 leaking an Activity reference.
     * @param callback receives pressure readings or an "unavailable" signal.
     *                 Must not be null.
     */
    public BarometerReader(Context context, BarometerCallback callback) {
        this.sensorManager  = (SensorManager) context.getApplicationContext()
                .getSystemService(Context.SENSOR_SERVICE);
        this.pressureSensor = (sensorManager != null)
                ? sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE)
                : null;
        this.callback       = callback;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Checks whether this device has a barometer at all.
     * Called before startReading() so the verification engine can decide
     * early whether to include pressure data in its scoring — avoids
     * waiting for a timeout on hardware that will never deliver a sample.
     *
     * @return true if TYPE_PRESSURE sensor exists on this device.
     */
    public boolean isAvailable() {
        return pressureSensor != null;
    }

    /**
     * Begins listening for barometer events at {@code SENSOR_DELAY_NORMAL}
     * (~200 ms between samples) — fast enough for a single room-check but
     * gentle on battery during a multi-minute attendance session.
     *
     * If the sensor is absent, fires {@code onBarometerUnavailable()} once
     * and returns immediately — no exception, no crash.
     *
     * Safe to call from any thread; synchronized internally.
     */
    public void startReading() {
        synchronized (lock) {
            if (listening) return;                         // idempotent

            if (pressureSensor == null) {
                callback.onBarometerUnavailable();
                return;
            }

            sensorManager.registerListener(
                    sensorListener,
                    pressureSensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
            listening = true;
        }
    }

    /**
     * Stops listening and releases the sensor so Android can power it down.
     * Must be called when the session ends or the Activity is destroyed —
     * failing to unregister keeps the sensor awake and drains battery.
     *
     * Safe to call from any thread; safe to call even if never started.
     */
    public void stopReading() {
        synchronized (lock) {
            if (!listening) return;                        // idempotent

            sensorManager.unregisterListener(sensorListener);
            listening = false;
        }
    }

    // ── Static utilities ──────────────────────────────────────────────────

    /**
     * Quick check: are two readings close enough to be on the same floor?
     *
     * Why 0.30 hPa? One storey ≈ 3 m ≈ 0.36 hPa. Sensor noise on budget
     * phones is ±0.10 hPa. A 0.30 threshold gives a comfortable margin
     * within one floor while reliably rejecting a full-storey difference.
     *
     * @param profHPa    professor's latest barometer reading (hPa).
     * @param studentHPa student's latest barometer reading (hPa).
     * @return true if the absolute pressure difference is below the threshold.
     */
    public static boolean isSameFloor(float profHPa, float studentHPa) {
        return Math.abs(profHPa - studentHPa) < SAME_FLOOR_THRESHOLD_HPA;
    }

    /**
     * Converts a pressure difference into an approximate altitude difference.
     *
     * Useful for debug/UI purposes ("~2.4 m apart vertically") but NOT used
     * as the primary verification signal — {@link #isSameFloor} is the
     * authoritative boolean check.
     *
     * @param hPaDiff absolute pressure difference in hectopascals.
     * @return approximate altitude difference in metres (always positive).
     */
    public static float pressureToAltitudeDiff(float hPaDiff) {
        return Math.abs(hPaDiff) * METRES_PER_HPA;
    }
}
