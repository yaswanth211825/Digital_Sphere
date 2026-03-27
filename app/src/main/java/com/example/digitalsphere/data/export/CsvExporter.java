package com.example.digitalsphere.data.export;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Exports attendance records to a CSV file.
 *
 * Bug fixes (QA):
 *   B-01  Use getExternalFilesDir() — no WRITE_EXTERNAL_STORAGE needed on any API.
 *         Old code used getExternalStoragePublicDirectory() which throws SecurityException
 *         on Android 10+ due to scoped-storage enforcement.
 *   B-02  File I/O runs on a background thread; Toast posted back to the main thread.
 *         Old code blocked the UI thread and could crash if called off it.
 *   B-03  Parses display strings into separate, properly-quoted CSV columns.
 *         Old code wrote the raw display string ("1. Name  •  Timestamp") as a single
 *         column — malformed because the timestamp itself contains a comma.
 *
 * FR-18: professor can export session attendance as CSV.
 * Output: <app-external>/Documents/attendance_<sessionId>.csv
 */
public class CsvExporter {

    private static final String ARCHIVE_DIR_NAME = "attendance_archives";
    private static final SimpleDateFormat ARCHIVE_TS_FMT =
            new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    private CsvExporter() {}

    /**
     * @param records formatted display strings from IAttendanceRepository.getAttendance()
     *                e.g. "1. Yash Sharma  •  25 Mar 2026, 03:30 PM"
     */
    public static void export(Context context, List<String> records, String sessionId) {
        // B-02: run file I/O on a background thread
        new Thread(() -> {
            try {
                File file = createOutputFile(context, sessionId);
                writeRecords(file, records);
                showToast(context, "✅ Exported: " + file.getName()
                        + "\nSaved in: " + file.getParent());
            } catch (IOException e) {
                showToast(context, "Export failed: " + e.getMessage());
            }
        }).start();
    }

    /**
     * Saves a session snapshot into app-internal storage. The file name includes
     * a timestamp so consecutive runs of the same session ID do not overwrite
     * each other.
     */
    public static File archiveToInternalStorage(Context context, List<String> records, String sessionId)
            throws IOException {
        File dir = getInternalArchiveDirectory(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create internal archive directory.");
        }

        File file = new File(dir,
                "attendance_" + sanitizeSessionId(sessionId) + "_"
                        + ARCHIVE_TS_FMT.format(new Date()) + ".csv");
        writeRecords(file, records);
        return file;
    }

    public static File getInternalArchiveDirectory(Context context) {
        return new File(context.getFilesDir(), ARCHIVE_DIR_NAME);
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /** B-01: App-private external dir — no permission required on any API level. */
    private static File createOutputFile(Context context, String sessionId)
            throws IOException {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (dir == null) throw new IOException("External storage is unavailable.");
        if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create output directory.");
        return new File(dir, "attendance_" + sessionId + ".csv");
    }

    /** B-03: Parse display strings into properly-quoted CSV columns. */
    private static void writeRecords(File file, List<String> records) throws IOException {
        try (FileWriter writer = new FileWriter(file)) {
            writer.write("Row,Student Name,Timestamp\n");

            for (String record : records) {
                // Display format: "1. Yash Sharma  •  25 Mar 2026, 03:30 PM"
                String rowNum = record.replaceFirst("^(\\d+)\\..*$", "$1");
                String body   = record.replaceFirst("^\\d+\\.\\s+", "");
                // Split on two-space + bullet + two-space separator
                String[] parts = body.split("  \u2022  ", 2);
                String name    = parts.length > 0 ? parts[0].trim() : "";
                String ts      = parts.length > 1 ? parts[1].trim() : "";

                writer.write(csvRow(rowNum, name, ts) + "\n");
            }
        }
    }

    /**
     * RFC 4180-compliant CSV row builder.
     * Wraps any field containing a comma, double-quote, or newline in double-quotes;
     * embedded double-quotes are escaped by doubling ("" inside "").
     */
    private static String csvRow(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append(',');
            String f = (fields[i] == null) ? "" : fields[i];
            if (f.contains(",") || f.contains("\"") || f.contains("\n")) {
                sb.append('"').append(f.replace("\"", "\"\"")).append('"');
            } else {
                sb.append(f);
            }
        }
        return sb.toString();
    }

    /** B-02: Always post Toast back to the main thread regardless of caller thread. */
    private static void showToast(Context context, String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(context.getApplicationContext(),
                        message, Toast.LENGTH_LONG).show());
    }

    private static String sanitizeSessionId(String sessionId) {
        if (sessionId == null || sessionId.trim().isEmpty()) return "session";
        return sessionId.replaceAll("[^a-zA-Z0-9_-]", "_");
    }
}
