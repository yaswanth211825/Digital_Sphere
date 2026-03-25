package com.example.digitalsphere.data.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Raw SQLite data source. Only accessed through AttendanceRepository.
 * No business logic here — just CRUD.
 */
public class AttendanceDB extends SQLiteOpenHelper {

    private static final String DB_NAME    = "attendance.db";
    private static final int    DB_VERSION = 2;

    static final SimpleDateFormat TIMESTAMP_FMT =
            new SimpleDateFormat("dd MMM yyyy, hh:mm a", Locale.getDefault());

    public AttendanceDB(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    /** Package-private constructor for integration tests — uses a custom DB name. */
    AttendanceDB(Context context, String dbName) {
        super(context, dbName, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE attendance (" +
                "id           INTEGER PRIMARY KEY AUTOINCREMENT," +
                "student_name TEXT NOT NULL," +
                "device_id    TEXT NOT NULL," +
                "timestamp    TEXT NOT NULL," +
                "session_id   TEXT NOT NULL)");

        // DB-level uniqueness: one attendance record per device per session (NFR-13)
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_device_session " +
                "ON attendance(device_id, session_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldV, int newV) {
        db.execSQL("DROP TABLE IF EXISTS attendance");
        onCreate(db);
    }

    boolean insert(String studentName, String deviceId, String sessionId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("student_name", studentName);
        v.put("device_id",    deviceId);
        v.put("timestamp",    TIMESTAMP_FMT.format(new Date()));
        v.put("session_id",   sessionId);
        long result = db.insertWithOnConflict("attendance", null, v,
                SQLiteDatabase.CONFLICT_IGNORE);
        return result != -1;
    }

    boolean exists(String deviceId, String sessionId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM attendance WHERE device_id=? AND session_id=?",
                new String[]{deviceId, sessionId});
        boolean found = false;
        if (c.moveToFirst()) found = c.getInt(0) > 0;
        c.close();
        return found;
    }

    List<String> queryBySession(String sessionId) {
        List<String> records = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT student_name, timestamp FROM attendance " +
                "WHERE session_id=? ORDER BY id ASC",
                new String[]{sessionId});
        int idx = 1;
        while (c.moveToNext()) {
            records.add(idx + ". " + c.getString(0) + "  \u2022  " + c.getString(1));
            idx++;
        }
        c.close();
        return records;
    }

    int countBySession(String sessionId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery(
                "SELECT COUNT(*) FROM attendance WHERE session_id=?",
                new String[]{sessionId});
        int count = 0;
        if (c.moveToFirst()) count = c.getInt(0);
        c.close();
        return count;
    }
}
