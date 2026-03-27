package com.example.digitalsphere.data.sensor;

import android.util.Log;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * SPRINT-1-UI: Research data logger for IEEE paper Table II.
 *
 * Logs structured sensor events to Android Logcat with tag "DS_RESEARCH".
 * Format: DS_RESEARCH | EVENT | value1 | value2 | result | timestamp
 *
 * This data is collected for offline analysis — NOT shown to the user.
 * Pure static utility — no instantiation, no state, no Android Views.
 */
public final class ResearchLogger {

    private static final String TAG = "DS_RESEARCH";

    private static final SimpleDateFormat TS_FMT =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    private ResearchLogger() {}  // utility class — no instances

    // ── Barometer events ────────────────────────────────────────────────

    /** Logs whether this device has a barometer sensor. */
    public static void logBarometerAvailability(boolean available, String role) {
        log("BARO_AVAILABLE", role, String.valueOf(available), "", "");
    }

    /** Logs a raw pressure reading from barometer. */
    public static void logPressureReading(String role, float hPa) {
        log("PRESSURE_READING", role, String.format(Locale.US, "%.2f", hPa), "hPa", "");
    }

    /** Logs the result of a same-floor comparison. */
    public static void logFloorCheck(float profHPa, float studentHPa, boolean sameFloor) {
        float diff = Math.abs(profHPa - studentHPa);
        float metres = BarometerReader.pressureToAltitudeDiff(diff);
        log("FLOOR_CHECK",
                String.format(Locale.US, "prof=%.2f", profHPa),
                String.format(Locale.US, "student=%.2f", studentHPa),
                String.format(Locale.US, "diff=%.2f hPa (~%.1fm)", diff, metres),
                sameFloor ? "SAME_FLOOR" : "DIFFERENT_FLOOR");
    }

    // ── Error events ────────────────────────────────────────────────────

    /** Logs any error state encountered during verification. */
    public static void logError(String errorType, String reason) {
        log("ERROR", errorType, reason, "", "");
    }

    /** Logs unstable pressure reading detection. */
    public static void logUnstableReading(float variance) {
        log("UNSTABLE_READING", String.format(Locale.US, "variance=%.4f", variance), "", "", "");
    }

    // ── Internal ────────────────────────────────────────────────────────

    private static void log(String event, String v1, String v2, String v3, String result) {
        String timestamp = TS_FMT.format(new Date());
        String line = String.format("%s | %s | %s | %s | %s | %s",
                TAG, event, v1, v2, result, timestamp);
        Log.i(TAG, line);
    }
}
