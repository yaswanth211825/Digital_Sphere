package com.example.digitalsphere;

import android.content.Context;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.example.digitalsphere.data.sensor.DiagLogger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

/**
 * Instrumented tests for DiagLogger — exercises every event type with
 * hardcoded values and verifies the GZIP JSON report pipeline end-to-end.
 *
 * Run on device/emulator:
 *   ./gradlew connectedAndroidTest --tests "*.DiagLoggerTest"
 *
 * All results are also written to Logcat under tag DS_DIAG and DS_DIAG_TEST.
 * Pull saved reports with:
 *   adb pull /data/data/com.example.digitalsphere/files/session_reports/ ./reports/
 * Open in Python:
 *   import gzip, json; d = json.loads(gzip.open('test_report.json.gz').read())
 */
@RunWith(AndroidJUnit4.class)
public class DiagLoggerTest {

    private static final String TAG = "DS_DIAG_TEST";

    private Context ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        // Re-initialise so each test starts with a clean event list.
        // init() is idempotent by design; force a reset by nulling appContext via reflection.
        forceReinit();
        DiagLogger.init(ctx);
    }

    // ── Test 1: Full professor session ────────────────────────────────────

    @Test
    public void testProfessorSessionReport() throws Exception {
        Log.i(TAG, "=== TEST: professor session report ===");

        // ── Device capability snapshot ──
        DiagLogger.logDeviceInfo();
        DiagLogger.logBleAdvertiseSupport();
        DiagLogger.logBleScanSupport();
        DiagLogger.logMicPermission(true);
        DiagLogger.logUnprocessedAudioSupport();
        DiagLogger.logBarometerPresent(true);
        DiagLogger.logAudioBufferSize();
        DiagLogger.logAudioRecordState();

        // ── Audio hash flow ──
        float[] profHash = {0.10f, 0.25f, 0.40f, 0.55f, 0.70f, 0.85f, 0.95f, 0.30f};
        byte[] payload   = new byte[12];
        payload[4] = 0x3F; payload[5] = 0x4D; payload[6] = 0x70; payload[7] = (byte) 0xA4;
        payload[8] = 0x1B; payload[9] = (byte) 0xC2; payload[10] = 0x55; payload[11] = 0x08;

        DiagLogger.logAudioHashRecorded(profHash);
        DiagLogger.logAudioHashPacked(payload);
        DiagLogger.logAudioHashInPayload(payload);
        DiagLogger.logAudioHashScanReceived(payload);
        DiagLogger.logAudioHashUnpacked(profHash);
        DiagLogger.logAudioHashDelivered(profHash);
        DiagLogger.logAudioHashStored(profHash);
        DiagLogger.logAudioHashUsed(profHash);
        DiagLogger.logAudioRecordStart(true);
        float[] studHash = {0.12f, 0.27f, 0.41f, 0.53f, 0.68f, 0.83f, 0.94f, 0.29f};
        DiagLogger.logAudioRecordDone(studHash);

        // ── Timing / verification flow ──
        DiagLogger.logScanStarted();
        DiagLogger.logFirstBeaconSeen(312);
        DiagLogger.logInRangeTriggered(-68, 750);
        DiagLogger.logVerifyFlowStart(45);
        DiagLogger.logBaroStepStart();
        DiagLogger.logBaroStepDone(280, 1013.25f);
        DiagLogger.logUltraStepStart();
        DiagLogger.logUltraStepDone(1400, 7, 0.82f);
        DiagLogger.logAudioStepStart();
        DiagLogger.logAudioStepDone(2000, 0.91f);
        DiagLogger.logDsvfStart();
        DiagLogger.logDsvfDone(18, "PRESENT", 0.87f);
        DiagLogger.logTotalVerifyTime(3743);
        DiagLogger.logAttendanceMarked(4055, "PRESENT");

        // ── Session context — 3 hardcoded students ──
        List<String> attendance = Arrays.asList(
                "1. Yash Sharma  •  05 Apr 2026, 02:30 PM",
                "2. Priya Nair   •  05 Apr 2026, 02:31 PM",
                "3. Karan Mehta  •  05 Apr 2026, 02:33 PM"
        );
        DiagLogger.setReportContext("CS101_0405", attendance);

        // ── Build report ──
        String json = DiagLogger.generateAndSaveReport("professor");
        assertNotNull("JSON must not be null", json);

        // ── Parse and assert structure ──
        JSONObject root = new JSONObject(json);

        assertEquals("version field should be 1", 1, root.getInt("v"));
        assertEquals("professor", root.getString("role"));
        assertFalse("launch_ts should not be empty", root.getString("launch_ts").isEmpty());
        assertEquals("CS101_0405", root.getString("session"));

        JSONObject dev = root.getJSONObject("dev");
        assertFalse("device model missing", dev.getString("model").isEmpty());
        assertFalse("device mfr missing", dev.getString("mfr").isEmpty());
        assertTrue("sdk must be > 0", dev.getInt("sdk") > 0);

        JSONArray ev = root.getJSONArray("ev");
        assertTrue("Expected >= 26 events, got " + ev.length(), ev.length() >= 26);

        // Each event row must have 5 fields: [relMs, event, detail, value, result]
        for (int i = 0; i < ev.length(); i++) {
            JSONArray row = ev.getJSONArray(i);
            assertEquals("Row " + i + " must have 5 fields", 5, row.length());
            assertTrue("relMs must be >= 0", row.getLong(0) >= 0);
            assertFalse("event name empty at row " + i, row.getString(1).isEmpty());
        }

        JSONArray att = root.getJSONArray("att");
        assertEquals("attendance count mismatch", 3, att.length());
        assertTrue("first entry should contain 'Yash Sharma'",
                att.getString(0).contains("Yash Sharma"));
        assertTrue("third entry should contain 'Karan Mehta'",
                att.getString(2).contains("Karan Mehta"));

        // ── GZIP round-trip check ──
        File reportFile = findLatestTestReport("professor");
        assertNotNull("No professor test report file found", reportFile);
        String recovered = decompressGzip(reportFile);
        assertEquals("Decompressed content must match original JSON", json, recovered);

        long rawBytes = json.getBytes("UTF-8").length;
        long gzBytes  = reportFile.length();
        double ratio  = 100.0 * (1.0 - (double) gzBytes / rawBytes);
        Log.i(TAG, String.format("PROFESSOR REPORT — events: %d, att: %d, raw: %d B, gz: %d B, savings: %.1f%%",
                ev.length(), att.length(), rawBytes, gzBytes, ratio));
        Log.i(TAG, "File: " + reportFile.getAbsolutePath());
        assertTrue("GZIP should reduce size by at least 40%", ratio > 40.0);

        Log.i(TAG, "=== PASSED: professor session report ===");
    }

    // ── Test 2: Student session (no barometer, no prof audio) ────────────

    @Test
    public void testStudentSessionReport_NoBaro_NoProfAudio() throws Exception {
        Log.i(TAG, "=== TEST: student session — degraded device ===");

        DiagLogger.logDeviceInfo();
        DiagLogger.logBleAdvertiseSupport();
        DiagLogger.logBleScanSupport();
        DiagLogger.logMicPermission(true);
        DiagLogger.logBarometerPresent(false); // no barometer
        DiagLogger.logAudioBufferSize();
        DiagLogger.logAudioRecordState();

        DiagLogger.logScanStarted();
        DiagLogger.logFirstBeaconSeen(490);
        DiagLogger.logInRangeTriggered(-72, 1100);
        DiagLogger.logVerifyFlowStart(30);

        // Barometer step — skipped (unavailable)
        DiagLogger.logBaroStepStart();
        DiagLogger.logBaroStepDone(5, 0.0f); // 0.0 = sentinel for unavailable

        // Ultrasound — timed out (budget device)
        DiagLogger.logUltraStepStart();
        DiagLogger.logUltraStepDone(3998, 0, 0.0f); // ~4000ms timeout

        // Audio — skipped because professor hash is null
        DiagLogger.logAudioHashUsed(null);  // null → NULL_SKIPPING

        // DSVF still passes on BLE alone
        DiagLogger.logDsvfStart();
        DiagLogger.logDsvfDone(15, "PRESENT", 0.76f);
        DiagLogger.logTotalVerifyTime(5128);
        DiagLogger.logAttendanceMarked(5620, "PRESENT");

        DiagLogger.setReportContext("CS101_0405", null); // student — no att list

        String json = DiagLogger.generateAndSaveReport("student");
        assertNotNull(json);

        JSONObject root = new JSONObject(json);
        assertEquals("student", root.getString("role"));
        assertEquals(0, root.getJSONArray("att").length()); // student always sends empty att

        JSONArray ev = root.getJSONArray("ev");
        assertTrue("Expected >= 13 events, got " + ev.length(), ev.length() >= 13);

        // Check AUDIO_HASH_USED result is NULL_SKIPPING
        boolean foundHashUsed = false;
        for (int i = 0; i < ev.length(); i++) {
            JSONArray row = ev.getJSONArray(i);
            if ("AUDIO_HASH_USED".equals(row.getString(1))) {
                assertEquals("NULL_SKIPPING", row.getString(4)); // result field
                foundHashUsed = true;
            }
        }
        assertTrue("AUDIO_HASH_USED event should be in ev array", foundHashUsed);

        // Check ULTRA_STEP_DONE result shows ~timeout
        boolean foundUltra = false;
        for (int i = 0; i < ev.length(); i++) {
            JSONArray row = ev.getJSONArray(i);
            if ("ULTRA_STEP_DONE".equals(row.getString(1))) {
                assertTrue("Ultrasound timeout should be >= 3900ms",
                        Long.parseLong(row.getString(2).replace("durationMs=", "")) >= 3900);
                foundUltra = true;
            }
        }
        assertTrue("ULTRA_STEP_DONE event should be in ev array", foundUltra);

        Log.i(TAG, "Student report — events: " + ev.length()
                + ", att: " + root.getJSONArray("att").length());
        Log.i(TAG, "=== PASSED: student degraded-device report ===");
    }

    // ── Test 3: Null/all-zero hash handling ──────────────────────────────

    @Test
    public void testNullHashAndAllZeroPayload() throws Exception {
        Log.i(TAG, "=== TEST: null hash and all-zero payload ===");

        DiagLogger.logDeviceInfo();
        DiagLogger.logAudioHashRecorded(null);                   // null hash
        DiagLogger.logAudioHashPacked(new byte[12]);             // all-zero payload
        DiagLogger.logAudioHashInPayload(new byte[12]);          // all-zero
        DiagLogger.logAudioHashScanReceived(null);               // null manufData
        DiagLogger.logAudioHashUnpacked(null);                   // null result
        DiagLogger.logAudioHashDelivered(null);
        DiagLogger.logAudioHashStored(null);
        DiagLogger.logAudioHashUsed(null);

        DiagLogger.setReportContext("NULL_HASH_SESSION", null);

        String json = DiagLogger.generateAndSaveReport("professor");
        assertNotNull(json);

        JSONObject root = new JSONObject(json);
        JSONArray ev = root.getJSONArray("ev");
        assertTrue(ev.length() >= 9);

        // Verify the diagnostic result values are correct
        assertEventResult(ev, "AUDIO_HASH_RECORDED", "ALL_ZERO");
        assertEventResult(ev, "AUDIO_HASH_PACKED", "ALL_ZERO");
        assertEventResult(ev, "AUDIO_HASH_IN_PAYLOAD", "NO_HASH");
        assertEventResult(ev, "AUDIO_HASH_SCAN_RECEIVED", "NULL");
        assertEventResult(ev, "AUDIO_HASH_UNPACKED", "NULL");
        assertEventResult(ev, "AUDIO_HASH_STORED", "NULL_STORED");
        assertEventResult(ev, "AUDIO_HASH_USED", "NULL_SKIPPING");

        Log.i(TAG, "=== PASSED: null hash handling ===");
    }

    // ── Test 4: Error events ─────────────────────────────────────────────

    @Test
    public void testErrorEvents() throws Exception {
        Log.i(TAG, "=== TEST: error events ===");

        DiagLogger.logDeviceInfo();
        DiagLogger.logBleAdvertiseError(2, "ADVERTISE_FAILED_TOO_MANY_ADVERTISERS");
        DiagLogger.logBleScanError(1);
        DiagLogger.logUltrasoundEmitFailed("AudioTrack failed to initialize");
        DiagLogger.logAmbientRecordFailed("AudioRecord state not initialized — OEM block");

        DiagLogger.setReportContext("ERROR_SESSION", null);

        String json = DiagLogger.generateAndSaveReport("professor");
        assertNotNull(json);

        JSONObject root = new JSONObject(json);
        JSONArray ev = root.getJSONArray("ev");

        assertEventResult(ev, "BLE_ADVERTISE_ERROR", "FAILED");
        assertEventResult(ev, "BLE_SCAN_ERROR", "FAILED");
        assertEventResult(ev, "ULTRASOUND_EMIT_FAILED", "FAILED");
        assertEventResult(ev, "AMBIENT_RECORD_FAILED", "FAILED");

        Log.i(TAG, "Error events confirmed in JSON. Events: " + ev.length());
        Log.i(TAG, "=== PASSED: error events ===");
    }

    // ── Test 5: Compression ratio on realistic session ────────────────────

    @Test
    public void testGzipCompressionRatio() throws Exception {
        Log.i(TAG, "=== TEST: GZIP compression ratio ===");

        // Simulate a 40-event session (realistic for a mid-length session)
        DiagLogger.logDeviceInfo();
        DiagLogger.logBleAdvertiseSupport();
        DiagLogger.logBleScanSupport();
        DiagLogger.logMicPermission(true);
        DiagLogger.logUnprocessedAudioSupport();
        DiagLogger.logBarometerPresent(true);
        DiagLogger.logAudioBufferSize();
        DiagLogger.logAudioRecordState();
        float[] h = {0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f};
        DiagLogger.logAudioHashRecorded(h);
        DiagLogger.logAudioHashPacked(new byte[12]);
        DiagLogger.logAudioHashInPayload(new byte[12]);
        DiagLogger.logAudioHashScanReceived(new byte[12]);
        DiagLogger.logAudioHashUnpacked(h);
        DiagLogger.logAudioHashDelivered(h);
        DiagLogger.logAudioHashStored(h);
        DiagLogger.logAudioHashUsed(h);
        DiagLogger.logAudioRecordStart(true);
        DiagLogger.logAudioRecordDone(h);
        DiagLogger.logScanStarted();
        DiagLogger.logFirstBeaconSeen(312);
        DiagLogger.logInRangeTriggered(-68, 750);
        DiagLogger.logVerifyFlowStart(45);
        DiagLogger.logBaroStepStart();
        DiagLogger.logBaroStepDone(280, 1013.25f);
        DiagLogger.logUltraStepStart();
        DiagLogger.logUltraStepDone(1400, 7, 0.82f);
        DiagLogger.logAudioStepStart();
        DiagLogger.logAudioStepDone(2000, 0.91f);
        DiagLogger.logDsvfStart();
        DiagLogger.logDsvfDone(18, "PRESENT", 0.87f);
        DiagLogger.logTotalVerifyTime(3743);
        DiagLogger.logAttendanceMarked(4055, "PRESENT");

        List<String> largeAtt = Arrays.asList(
                "1. Yash Sharma  •  05 Apr 2026, 02:30 PM",
                "2. Priya Nair   •  05 Apr 2026, 02:31 PM",
                "3. Karan Mehta  •  05 Apr 2026, 02:33 PM",
                "4. Asha Verma   •  05 Apr 2026, 02:34 PM",
                "5. Ravi Gupta   •  05 Apr 2026, 02:35 PM",
                "6. Meena Joshi  •  05 Apr 2026, 02:36 PM",
                "7. Arjun Patel  •  05 Apr 2026, 02:37 PM",
                "8. Sunita Rao   •  05 Apr 2026, 02:38 PM",
                "9. Deepak Singh •  05 Apr 2026, 02:39 PM",
                "10. Kavya Reddy •  05 Apr 2026, 02:40 PM"
        );
        DiagLogger.setReportContext("CS101_0405_LARGE", largeAtt);

        String json = DiagLogger.generateAndSaveReport("professor");
        assertNotNull(json);

        File f = findLatestTestReport("professor");
        assertNotNull(f);

        long rawBytes = json.getBytes("UTF-8").length;
        long gzBytes  = f.length();
        double ratio  = 100.0 * (1.0 - (double) gzBytes / rawBytes);

        Log.i(TAG, String.format("Compression: raw=%d B, gz=%d B, savings=%.1f%%",
                rawBytes, gzBytes, ratio));
        Log.i(TAG, "JSON (first 500 chars): " + json.substring(0, Math.min(500, json.length())));

        assertTrue("Should compress by at least 50% on realistic data", ratio > 50.0);
        assertTrue("Compressed file must be < 10 KB", gzBytes < 10_000);
        Log.i(TAG, "=== PASSED: GZIP compression ratio ===");
    }

    // ── Test 6: Student report — no mic permission ────────────────────────

    @Test
    public void testStudentNoMicPermission() throws Exception {
        Log.i(TAG, "=== TEST: student — no mic ===");

        DiagLogger.logDeviceInfo();
        DiagLogger.logMicPermission(false);              // mic denied
        DiagLogger.logBarometerPresent(true);
        DiagLogger.logScanStarted();
        DiagLogger.logFirstBeaconSeen(200);
        DiagLogger.logInRangeTriggered(-65, 600);
        DiagLogger.logVerifyFlowStart(20);
        DiagLogger.logBaroStepStart();
        DiagLogger.logBaroStepDone(260, 1012.50f);
        DiagLogger.logDsvfStart();
        DiagLogger.logDsvfDone(12, "PRESENT", 0.78f);
        DiagLogger.logTotalVerifyTime(1300);
        DiagLogger.logAttendanceMarked(1800, "PRESENT");

        DiagLogger.setReportContext("CS101_NO_MIC", null);
        String json = DiagLogger.generateAndSaveReport("student");
        assertNotNull(json);

        JSONObject root = new JSONObject(json);
        assertEventResult(root.getJSONArray("ev"), "MIC_PERMISSION", "DENIED");

        Log.i(TAG, "=== PASSED: student no-mic report ===");
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Finds the most recently modified test_*.json.gz for the given role. */
    private File findLatestTestReport(String role) {
        File dir = new File(ctx.getFilesDir(), "session_reports");
        if (!dir.exists()) return null;
        File[] files = dir.listFiles((d, name) ->
                name.startsWith("test_") && name.contains("_" + role + "_") && name.endsWith(".json.gz"));
        if (files == null || files.length == 0) return null;
        File latest = files[0];
        for (File f : files) if (f.lastModified() > latest.lastModified()) latest = f;
        return latest;
    }

    /** Decompresses a .json.gz file and returns its content as a UTF-8 string. */
    private String decompressGzip(File file) throws IOException {
        byte[] buf = new byte[2048];
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPInputStream gis = new GZIPInputStream(new FileInputStream(file))) {
            int n;
            while ((n = gis.read(buf)) != -1) baos.write(buf, 0, n);
        }
        return baos.toString("UTF-8");
    }

    /** Asserts that the last event matching eventName has the given result (field index 4). */
    private void assertEventResult(JSONArray ev, String eventName, String expectedResult)
            throws Exception {
        for (int i = ev.length() - 1; i >= 0; i--) {
            JSONArray row = ev.getJSONArray(i);
            if (eventName.equals(row.getString(1))) {
                assertEquals("Result mismatch for " + eventName,
                        expectedResult, row.getString(4));
                return;
            }
        }
        fail("Event '" + eventName + "' not found in ev array");
    }

    /**
     * Forces DiagLogger to reset its static state so each test gets a clean slate.
     * Uses reflection to null the appContext field — tests only.
     */
    private void forceReinit() {
        try {
            java.lang.reflect.Field f = DiagLogger.class.getDeclaredField("appContext");
            f.setAccessible(true);
            f.set(null, null);
        } catch (Exception e) {
            Log.w(TAG, "forceReinit: could not reset DiagLogger — " + e.getMessage());
        }
    }
}
