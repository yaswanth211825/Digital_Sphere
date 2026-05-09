package com.example.digitalsphere.data.sensor;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;
import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.GZIPOutputStream;

/**
 * Structured diagnostic logger for DigitalSphere research data collection.
 *
 * Writes every event to:
 *   1. Android Logcat (tag DS_DIAG) — visible via adb
 *   2. A CSV file at <filesDir>/diag_logs/diag_<timestamp>_<model>.csv
 *      — persists on device, survives app restart, shareable without adb
 *   3. An in-memory list — flushed as GZIP-compressed JSON to
 *      <filesDir>/session_reports/ and emailed on sendReport()
 *
 * Usage:
 *   1. Call DiagLogger.init(context) once in MainActivity.onCreate()
 *   2. Call DiagLogger.setReportContext(sessionId, records) from presenter
 *      before the session-end view callback fires
 *   3. Call DiagLogger.promptAndSend(activity, role) from onSessionStopped()
 *      or onAttendanceMarked() to prompt the user
 *   4. To share raw CSV: DiagLogger.shareLogs(activity)
 */
public final class DiagLogger {

    private static final String TAG              = "DS_DIAG";
    private static final String AUTHORITY_SUFFIX = ".fileprovider";
    private static final String RESEARCHER_EMAIL = "yaswanthbopparaju@gmail.com";

    // Initialized once via init() — never null after that
    private static volatile android.content.Context appContext = null;

    // One CSV file per app launch
    private static volatile File logFile = null;

    // Lock for file writes — BLE callback and audio threads both call log()
    private static final Object FILE_LOCK = new Object();

    // In-memory event list — each entry: [relMs(Long), event, detail, value, result]
    private static final List<Object[]> EVENT_LIST = new ArrayList<>();
    private static final Object LIST_LOCK = new Object();
    private static long launchTimeMs = 0;

    // Session context set just before promptAndSend() is called
    private static volatile String       pendingSessionId   = null;
    private static volatile List<String> pendingAttendance  = null;

    // ThreadLocal SimpleDateFormat — avoids lock contention across threads
    private static final ThreadLocal<SimpleDateFormat> TS_FMT =
            ThreadLocal.withInitial(() -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.US));
    private static final SimpleDateFormat LAUNCH_TS_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);

    private DiagLogger() {}

    // ── Initialisation ────────────────────────────────────────────────────

    /**
     * Call once from MainActivity.onCreate() with the Application context.
     * Creates a new CSV file and resets the in-memory event list.
     * Safe to call multiple times — only the first call takes effect.
     */
    public static synchronized void init(android.content.Context context) {
        if (appContext != null) return;
        appContext     = context.getApplicationContext();
        launchTimeMs   = System.currentTimeMillis();
        synchronized (LIST_LOCK) { EVENT_LIST.clear(); }
        pendingSessionId  = null;
        pendingAttendance = null;
        openLogFile();
    }

    private static void openLogFile() {
        try {
            File dir = new File(appContext.getFilesDir(), "diag_logs");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();

            String modelSafe = (Build.MANUFACTURER + "_" + Build.MODEL)
                    .replaceAll("[^a-zA-Z0-9_\\-]", "_");
            String launch = LAUNCH_TS_FMT.format(new Date());
            logFile = new File(dir, "diag_" + launch + "_" + modelSafe + ".csv");

            try (FileWriter fw = new FileWriter(logFile, false)) {
                fw.write("timestamp,model,android_version,sdk_int,event,detail,value,result\n");
            }
        } catch (IOException e) {
            Log.w(TAG, "DiagLogger: failed to create log file — " + e.getMessage());
        }
    }

    // ── Core log ─────────────────────────────────────────────────────────

    public static void log(String event, String detail, String value, String result) {
        String ts    = TS_FMT.get().format(new Date());
        String model = "jvm_test";
        String androidVersion = "";
        int sdkInt = -1;

        try {
            model = (Build.MANUFACTURER + "_" + Build.MODEL).replace("|", "/");
            androidVersion = Build.VERSION.RELEASE;
            sdkInt = Build.VERSION.SDK_INT;

            // 1. Logcat
            Log.i(TAG, ts + " | " + model + " | " + event
                    + " | " + detail + " | " + value + " | " + result);
        } catch (Throwable ignored) {
            // Unit tests run on the plain JVM where android.os.Build / Log are stubs.
            // Logging must never crash the verification flow.
        }

        // 2. CSV file
        if (logFile != null) {
            String row = csv(ts) + ","
                    + csv(model) + ","
                    + csv(androidVersion) + ","
                    + sdkInt + ","
                    + csv(event) + ","
                    + csv(detail) + ","
                    + csv(value) + ","
                    + csv(result) + "\n";
            synchronized (FILE_LOCK) {
                try (FileWriter fw = new FileWriter(logFile, true)) {
                    fw.write(row);
                } catch (IOException ignored) {
                    // Never crash the app over a logging failure
                }
            }
        }

        // 3. In-memory list for JSON report — [relMs, event, detail, value, result]
        synchronized (LIST_LOCK) {
            EVENT_LIST.add(new Object[]{
                    System.currentTimeMillis() - launchTimeMs,
                    event, detail, value, result });
        }
    }

    public static void log(String event, String detail) {
        log(event, detail, "", "");
    }

    // ── Session report — GZIP JSON + email ───────────────────────────────

    /**
     * Stores the session ID and attendance list for the upcoming report.
     * Call from ProfessorPresenter.stopSession() and StudentPresenter.beginAttendanceMark()
     * BEFORE the view callback fires.
     */
    public static synchronized void setReportContext(String sessionId, List<String> records) {
        pendingSessionId  = sessionId;
        pendingAttendance = records != null ? new ArrayList<>(records) : new ArrayList<>();
    }

    /**
     * Shows an AlertDialog asking the user to send session data to the researcher.
     * On confirm, builds a GZIP-compressed JSON report and opens the email chooser.
     *
     * Wire to ProfessorActivity.onSessionStopped() and StudentActivity.onAttendanceMarked().
     */
    public static void promptAndSend(Activity activity, String role) {
        if (appContext == null) return;
        new AlertDialog.Builder(activity)
                .setTitle("Share Session Data")
                .setMessage("Send session diagnostics to the researcher?\n\n"
                        + "This helps improve the study. The file is very small (< 5 KB) "
                        + "and contains no personal data beyond your name and device model.")
                .setPositiveButton("Send", (d, w) -> sendReport(activity, role))
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show();
    }

    private static void sendReport(Activity activity, final String role) {
        new Thread(() -> {
            try {
                // 1. Build compact JSON
                String json = buildJson(role);

                // 2. GZIP compress
                byte[] compressed = gzipBytes(json);

                // 3. Save to session_reports/
                File dir = new File(appContext.getFilesDir(), "session_reports");
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();

                String sessionSafe = (pendingSessionId != null)
                        ? pendingSessionId.replaceAll("[^a-zA-Z0-9_\\-]", "_")
                        : "unknown";
                String launch   = LAUNCH_TS_FMT.format(new Date(launchTimeMs));
                String filename = "report_" + launch + "_" + role + "_" + sessionSafe + ".json.gz";
                File outFile = new File(dir, filename);
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(compressed);
                }

                // 4. Get FileProvider URI
                String authority = appContext.getPackageName() + AUTHORITY_SUFFIX;
                Uri fileUri = FileProvider.getUriForFile(appContext, authority, outFile);

                // 5. Build email subject and body
                String subject = buildSubject(role, sessionSafe);
                String body    = buildBody(role);

                // 6. Open email chooser on main thread
                Intent email = new Intent(Intent.ACTION_SEND);
                email.setType("application/gzip");
                email.putExtra(Intent.EXTRA_EMAIL,   new String[]{ RESEARCHER_EMAIL });
                email.putExtra(Intent.EXTRA_SUBJECT,  subject);
                email.putExtra(Intent.EXTRA_TEXT,     body);
                email.putExtra(Intent.EXTRA_STREAM,   fileUri);
                email.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Intent chooser = Intent.createChooser(email, "Send session report…");

                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        activity.startActivity(chooser);
                    } catch (Exception e) {
                        Toast.makeText(appContext,
                                "No email app found — report saved to session_reports/",
                                Toast.LENGTH_LONG).show();
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "sendReport failed: " + e.getMessage(), e);
                new Handler(Looper.getMainLooper()).post(() ->
                        Toast.makeText(appContext,
                                "Failed to prepare report: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static String buildJson(String role) throws JSONException {
        JSONObject root = new JSONObject();
        root.put("v",         1);
        root.put("role",      role);
        root.put("launch_ts", LAUNCH_TS_FMT.format(new Date(launchTimeMs)));

        JSONObject dev = new JSONObject();
        dev.put("mfr",     Build.MANUFACTURER);
        dev.put("model",   Build.MODEL);
        dev.put("android", Build.VERSION.RELEASE);
        dev.put("sdk",     Build.VERSION.SDK_INT);
        root.put("dev", dev);

        root.put("session", pendingSessionId != null ? pendingSessionId : "");

        // Events snapshot — rows: [relMs, event, detail, value, result]
        List<Object[]> snapshot;
        synchronized (LIST_LOCK) {
            snapshot = new ArrayList<>(EVENT_LIST);
        }
        JSONArray evArr = new JSONArray();
        for (Object[] ev : snapshot) {
            JSONArray row = new JSONArray();
            row.put(((Long) ev[0]).longValue()); // relMs
            row.put((String) ev[1]);             // event
            row.put((String) ev[2]);             // detail
            row.put((String) ev[3]);             // value
            row.put((String) ev[4]);             // result
            evArr.put(row);
        }
        root.put("ev", evArr);

        // Attendance list (professor only; student sends empty array)
        JSONArray attArr = new JSONArray();
        if (pendingAttendance != null) {
            for (String entry : pendingAttendance) attArr.put(entry);
        }
        root.put("att", attArr);

        return root.toString();
    }

    private static byte[] gzipBytes(String text) throws IOException {
        byte[] input = text.getBytes("UTF-8");
        ByteArrayOutputStream baos = new ByteArrayOutputStream(input.length);
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(input);
        }
        return baos.toByteArray();
    }

    private static String buildSubject(String role, String sessionSafe) {
        String dateStr = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        return "DigitalSphere | " + role.toUpperCase(Locale.US)
                + " | " + sessionSafe
                + " | " + Build.MANUFACTURER + " " + Build.MODEL
                + " | " + dateStr;
    }

    private static String buildBody(String role) {
        String roleCap = (role != null && !role.isEmpty())
                ? role.substring(0, 1).toUpperCase(Locale.US) + role.substring(1)
                : role;

        String timeStr = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());

        StringBuilder sb = new StringBuilder();
        sb.append("DigitalSphere Session Data\n");
        sb.append("--------------------------\n");
        sb.append("Role:    ").append(roleCap).append("\n");
        sb.append("Session: ").append(pendingSessionId != null ? pendingSessionId : "—").append("\n");
        sb.append("Device:  ")
          .append(Build.MANUFACTURER).append(" ").append(Build.MODEL)
          .append(" (Android ").append(Build.VERSION.RELEASE)
          .append(", SDK ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Time:    ").append(timeStr).append("\n\n");
        sb.append("Session summary:\n");

        if ("professor".equalsIgnoreCase(role)) {
            int count = (pendingAttendance != null) ? pendingAttendance.size() : 0;
            sb.append("  Students present:     ").append(count).append("\n");
        }

        // DSVF result (status) + score (value field)
        String dsvfStatus = scanEventField("DSVF_DONE", 4, "—");   // result = status
        String dsvfScore  = scanEventField("DSVF_DONE", 3, "");    // value  = "score=0.87"
        sb.append("  DSVF result:          ").append(dsvfStatus);
        if (!dsvfScore.isEmpty()) sb.append(" (").append(dsvfScore).append(")");
        sb.append("\n");

        // Total verify time
        String totalMs = scanEventField("TOTAL_VERIFY_TIME", 2, "—"); // detail = "ms=3420"
        sb.append("  Total verify time:    ").append(totalMs).append("\n");

        // Audio hash — different key event per role
        if ("professor".equalsIgnoreCase(role)) {
            String hashInPayload = scanEventField("AUDIO_HASH_IN_PAYLOAD", 4, "—");
            sb.append("  Audio hash in BLE:    ")
              .append("HASH_PRESENT".equals(hashInPayload) ? "YES" : "NO").append("\n");
        } else {
            String hashStored = scanEventField("AUDIO_HASH_STORED", 4, "—");
            sb.append("  Audio hash received:  ")
              .append("STORED".equals(hashStored) ? "YES" : "NO").append("\n");
        }

        sb.append("\nAttachment: ").append("report_")
          .append(LAUNCH_TS_FMT.format(new Date(launchTimeMs)))
          .append("_").append(role).append("_")
          .append(pendingSessionId != null
                  ? pendingSessionId.replaceAll("[^a-zA-Z0-9_\\-]", "_") : "unknown")
          .append(".json.gz\n");
        sb.append("To open:  python -c \"import gzip,json; ")
          .append("d=json.loads(gzip.open('FILE').read()); print(d)\"\n");
        return sb.toString();
    }

    /**
     * Scans EVENT_LIST newest-first for the last event matching eventName.
     * fieldIdx: 0=relMs, 1=event, 2=detail, 3=value, 4=result
     */
    private static String scanEventField(String eventName, int fieldIdx, String fallback) {
        List<Object[]> snapshot;
        synchronized (LIST_LOCK) {
            snapshot = new ArrayList<>(EVENT_LIST);
        }
        for (int i = snapshot.size() - 1; i >= 0; i--) {
            Object[] ev = snapshot.get(i);
            if (eventName.equals(ev[1])) {
                Object field = ev[fieldIdx];
                String s = (field instanceof String) ? (String) field : String.valueOf(field);
                return (s != null && !s.isEmpty()) ? s : fallback;
            }
        }
        return fallback;
    }

    // ── Share all log files ───────────────────────────────────────────────

    /**
     * Shares all CSV files in the diag_logs directory via Android share sheet.
     * Wire to a button or long-press in any Activity.
     */
    public static void shareLogs(Activity activity) {
        if (appContext == null) {
            Toast.makeText(activity, "No logs yet — open the app first.", Toast.LENGTH_SHORT).show();
            return;
        }
        File dir = new File(appContext.getFilesDir(), "diag_logs");
        File[] files = dir.listFiles((d, name) -> name.endsWith(".csv"));
        if (files == null || files.length == 0) {
            Toast.makeText(activity, "No diagnostic logs found.", Toast.LENGTH_SHORT).show();
            return;
        }

        String authority = appContext.getPackageName() + AUTHORITY_SUFFIX;
        ArrayList<Uri> uris = new ArrayList<>();
        for (File f : files) {
            try {
                uris.add(FileProvider.getUriForFile(appContext, authority, f));
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "shareLogs: skipping " + f.getName() + " — " + e.getMessage());
            }
        }
        if (uris.isEmpty()) {
            Toast.makeText(activity, "Could not prepare log files for sharing.", Toast.LENGTH_SHORT).show();
            return;
        }

        Intent shareIntent;
        if (uris.size() == 1) {
            shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("text/csv");
            shareIntent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        } else {
            shareIntent = new Intent(Intent.ACTION_SEND_MULTIPLE);
            shareIntent.setType("text/csv");
            shareIntent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        shareIntent.putExtra(Intent.EXTRA_SUBJECT,
                "DigitalSphere Logs — " + Build.MANUFACTURER + " " + Build.MODEL);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(Intent.createChooser(shareIntent, "Share diagnostic logs via…"));
    }

    /**
     * Returns the directory where CSV files are stored.
     */
    public static File getLogDir() {
        if (appContext == null) return null;
        return new File(appContext.getFilesDir(), "diag_logs");
    }

    // ── Device capability (log once at app launch) ────────────────────────

    public static void logDeviceInfo() {
        log("DEVICE_INFO",
                "manufacturer=" + Build.MANUFACTURER + " model=" + Build.MODEL,
                "android=" + Build.VERSION.RELEASE,
                "sdk=" + Build.VERSION.SDK_INT);
    }

    public static void logBleAdvertiseSupport() {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        boolean ok = (a != null) && a.isMultipleAdvertisementSupported();
        log("BLE_ADVERTISE_SUPPORT", "isMultipleAdvSupported=" + ok, "", ok ? "SUPPORTED" : "NOT_SUPPORTED");
    }

    public static void logBleScanSupport() {
        boolean ok = BluetoothAdapter.getDefaultAdapter() != null;
        log("BLE_SCAN_SUPPORT", "", "", ok ? "AVAILABLE" : "NOT_AVAILABLE");
    }

    public static void logMicPermission(boolean granted) {
        log("MIC_PERMISSION", "", "", granted ? "GRANTED" : "DENIED");
    }

    public static void logUnprocessedAudioSupport() {
        boolean ok = Build.VERSION.SDK_INT >= 24;
        log("UNPROCESSED_AUDIO_SUPPORT", "sdk=" + Build.VERSION.SDK_INT, "", ok ? "SUPPORTED" : "NOT_SUPPORTED");
    }

    public static void logBarometerPresent(boolean present) {
        log("BAROMETER_PRESENT", "", "", present ? "YES" : "NO");
    }

    public static void logAudioBufferSize() {
        int size = AudioRecord.getMinBufferSize(
                48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        log("AUDIO_BUFFER_SIZE", "bytes=" + size, "", size > 0 ? "OK" : "BROKEN_" + size);
    }

    public static void logAudioRecordState() {
        int bufSize = AudioRecord.getMinBufferSize(
                48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (bufSize <= 0) {
            log("AUDIORECORD_STATE", "minBufferSize=" + bufSize, "", "BUFFER_ERROR");
            return;
        }
        AudioRecord probe = null;
        try {
            probe = new AudioRecord(MediaRecorder.AudioSource.MIC,
                    48000, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize * 2);
            int state = probe.getState();
            log("AUDIORECORD_STATE", "", "",
                    state == AudioRecord.STATE_INITIALIZED ? "INITIALIZED" : "ERROR_STATE_" + state);
        } catch (Exception e) {
            log("AUDIORECORD_STATE", "ex=" + e.getClass().getSimpleName(), "", "EXCEPTION");
        } finally {
            if (probe != null) { try { probe.release(); } catch (Exception ignored) {} }
        }
    }

    // ── Error events ──────────────────────────────────────────────────────

    public static void logBleAdvertiseError(int errorCode, String reason) {
        log("BLE_ADVERTISE_ERROR", "code=" + errorCode, reason, "FAILED");
    }

    public static void logBleScanError(int errorCode) {
        log("BLE_SCAN_ERROR", "code=" + errorCode, "", "FAILED");
    }

    public static void logUltrasoundEmitFailed(String reason) {
        log("ULTRASOUND_EMIT_FAILED", reason, "", "FAILED");
    }

    public static void logAmbientRecordFailed(String reason) {
        log("AMBIENT_RECORD_FAILED", reason, "", "FAILED");
    }

    // ── Audio hash flow — traces BUG 1 ───────────────────────────────────

    public static void logAudioHashRecorded(float[] hash) {
        int nz = countNonZero(hash);
        log("AUDIO_HASH_RECORDED",
                "len=" + (hash != null ? hash.length : 0),
                "nonZeroBands=" + nz,
                (hash != null && nz > 0) ? "OK" : "ALL_ZERO");
    }

    public static void logAudioHashPacked(byte[] payload) {
        if (payload == null) { log("AUDIO_HASH_PACKED", "payload=null", "", "NULL"); return; }
        int nz = 0;
        StringBuilder hex = new StringBuilder();
        for (int i = 4; i < Math.min(12, payload.length); i++) {
            if (payload[i] != 0) nz++;
            hex.append(String.format(Locale.US, "%02X", payload[i] & 0xFF));
        }
        log("AUDIO_HASH_PACKED", "payloadLen=" + payload.length, "bytes4-11=" + hex,
                nz > 0 ? "HAS_AUDIO" : "ALL_ZERO");
    }

    public static void logAudioHashInPayload(byte[] payload) {
        if (payload == null) { log("AUDIO_HASH_IN_PAYLOAD", "payload=null", "", "NULL"); return; }
        int nz = 0;
        for (int i = 4; i < Math.min(12, payload.length); i++) if (payload[i] != 0) nz++;
        log("AUDIO_HASH_IN_PAYLOAD", "payloadLen=" + payload.length,
                "audioBytes=" + nz + "/8", nz > 0 ? "HASH_PRESENT" : "NO_HASH");
    }

    public static void logAudioHashScanReceived(byte[] manufData) {
        if (manufData == null) {
            log("AUDIO_HASH_SCAN_RECEIVED", "manufData=null", "", "NULL");
        } else {
            log("AUDIO_HASH_SCAN_RECEIVED", "len=" + manufData.length, "", "OK");
        }
    }

    public static void logAudioHashUnpacked(float[] hash) {
        if (hash == null) { log("AUDIO_HASH_UNPACKED", "result=null", "", "NULL"); return; }
        log("AUDIO_HASH_UNPACKED", "len=" + hash.length, "nonZero=" + countNonZero(hash),
                countNonZero(hash) > 0 ? "VALID" : "ALL_ZERO");
    }

    public static void logAudioHashDelivered(float[] hash) {
        log("AUDIO_HASH_DELIVERED",
                "hash=" + (hash == null ? "null" : "float[" + hash.length + "]"),
                "", hash != null ? "NOT_NULL" : "NULL");
    }

    public static void logAudioHashStored(float[] hash) {
        log("AUDIO_HASH_STORED",
                "hash=" + (hash == null ? "null" : "float[" + hash.length + "]"),
                "", hash != null ? "STORED" : "NULL_STORED");
    }

    public static void logAudioHashUsed(float[] hash) {
        log("AUDIO_HASH_USED",
                "professorHash=" + (hash == null ? "null" : "float[" + hash.length + "]"),
                "", hash != null ? "WILL_RECORD" : "NULL_SKIPPING");
    }

    public static void logAudioRecordStart(boolean permissionOk) {
        log("AUDIO_RECORD_START", "", "", permissionOk ? "PERMISSION_OK" : "NO_PERMISSION");
    }

    public static void logAudioRecordDone(float[] hash) {
        int nz = countNonZero(hash);
        log("AUDIO_RECORD_DONE",
                "len=" + (hash != null ? hash.length : 0),
                "nonZeroBands=" + nz,
                (hash != null && nz > 0) ? "HASH_OK" : "EMPTY");
    }

    // ── Timing — traces BUG 2 ────────────────────────────────────────────

    public static void logScanStarted() {
        log("SCAN_STARTED", "", "", "");
    }

    public static void logFirstBeaconSeen(long msSinceScanStart) {
        log("FIRST_BEACON_SEEN", "ms=" + msSinceScanStart, "", "");
    }

    public static void logInRangeTriggered(int rssi, long msSinceScanStart) {
        log("IN_RANGE_TRIGGERED", "rssi=" + rssi, "ms=" + msSinceScanStart, "");
    }

    public static void logVerifyFlowStart(long msSinceInRange) {
        log("VERIFY_FLOW_START", "ms_since_inrange=" + msSinceInRange, "", "");
    }

    public static void logBaroStepStart() {
        log("BARO_STEP_START", "", "", "");
    }

    public static void logBaroStepDone(long durationMs, float pressureHPa) {
        log("BARO_STEP_DONE", "durationMs=" + durationMs,
                String.format(Locale.US, "%.2f_hPa", pressureHPa), "");
    }

    public static void logUltraStepStart() {
        log("ULTRA_STEP_START", "", "", "");
    }

    public static void logUltraStepDone(long durationMs, int token, float confidence) {
        log("ULTRA_STEP_DONE", "durationMs=" + durationMs,
                "token=" + token,
                String.format(Locale.US, "conf=%.2f", confidence));
    }

    public static void logAudioStepStart() {
        log("AUDIO_STEP_START", "", "", "");
    }

    public static void logAudioStepDone(long durationMs, float cosine) {
        log("AUDIO_STEP_DONE", "durationMs=" + durationMs,
                String.format(Locale.US, "cosine=%.3f", cosine), "");
    }

    /** Logs cosine and Pearson for diagnostic comparison.
     *  DSVF audio presence now uses cosine (cross-device robust);
     *  Pearson is logged for research analysis only. */
    public static void logAudioCorrelationDetail(float cosine, float pearson,
                                                  float[] profHash, float[] studHash) {
        String profShape = hashShape(profHash);
        String studShape = hashShape(studHash);
        log("AUDIO_CORRELATION",
                String.format(Locale.US, "cosine=%.3f pearson=%.3f", cosine, pearson),
                String.format(Locale.US, "presence=%.3f profShape=%s studShape=%s",
                        cosine, profShape, studShape),
                cosine >= 0.60f ? "MATCH" : cosine >= 0.40f ? "WEAK" : "MISMATCH");
    }

    private static String hashShape(float[] h) {
        if (h == null || h.length == 0) return "null";
        float max = 0f, min = 1f, sum = 0f;
        for (float v : h) { max = Math.max(max, v); min = Math.min(min, v); sum += v; }
        return String.format(Locale.US, "[mean=%.2f range=%.2f]", sum / h.length, max - min);
    }

    public static void logDsvfStart() {
        log("DSVF_START", "", "", "");
    }

    public static void logDsvfDone(long durationMs, String status, float score) {
        log("DSVF_DONE", "durationMs=" + durationMs,
                String.format(Locale.US, "score=%.2f", score), status);
    }

    public static void logTotalVerifyTime(long totalMs) {
        log("TOTAL_VERIFY_TIME", "ms=" + totalMs, "", "");
    }

    public static void logAttendanceMarked(long msSinceScanStart, String status) {
        log("ATTENDANCE_MARKED", "ms=" + msSinceScanStart, "", status);
    }

    // ── Test support ─────────────────────────────────────────────────────

    /**
     * Builds a GZIP JSON report from the current in-memory event list and the
     * pending session context, saves it to session_reports/, and returns the
     * raw (uncompressed) JSON string for assertion.
     *
     * Bypasses the AlertDialog — call this from instrumented tests or debug buttons.
     */
    public static String generateAndSaveReport(String role) {
        if (appContext == null) return null;
        try {
            String json = buildJson(role);
            byte[] gz   = gzipBytes(json);

            File dir = new File(appContext.getFilesDir(), "session_reports");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();

            String sessionSafe = (pendingSessionId != null)
                    ? pendingSessionId.replaceAll("[^a-zA-Z0-9_\\-]", "_")
                    : "unknown";
            String fname = "test_" + LAUNCH_TS_FMT.format(new Date(launchTimeMs))
                    + "_" + role + "_" + sessionSafe + ".json.gz";
            File out = new File(dir, fname);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(gz);
            }

            Log.i(TAG, "[TEST] Report saved: " + out.getAbsolutePath()
                    + " (" + gz.length + " bytes gz / " + json.length() + " chars raw)");
            Log.i(TAG, "[TEST] JSON: " + json);
            return json;
        } catch (Exception e) {
            Log.e(TAG, "generateAndSaveReport failed: " + e.getMessage(), e);
            return null;
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    private static int countNonZero(float[] arr) {
        if (arr == null) return 0;
        int n = 0;
        for (float v : arr) if (v != 0f) n++;
        return n;
    }

    /** RFC-4180 CSV field escaping. */
    private static String csv(String s) {
        if (s == null || s.isEmpty()) return "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n") || s.contains("\r")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
