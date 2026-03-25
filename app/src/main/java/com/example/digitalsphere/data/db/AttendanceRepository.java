package com.example.digitalsphere.data.db;

import android.content.Context;
import com.example.digitalsphere.domain.IAttendanceRepository;
import java.util.List;

/**
 * Concrete implementation of IAttendanceRepository backed by SQLite.
 * This is the only class that knows AttendanceDB exists.
 * Presenters talk to IAttendanceRepository only — they never see SQLite.
 */
public class AttendanceRepository implements IAttendanceRepository {

    private final AttendanceDB db;

    public AttendanceRepository(Context context) {
        this.db = new AttendanceDB(context);
    }

    @Override
    public boolean markPresent(String studentName, String deviceId, String sessionId) {
        if (isAlreadyMarked(deviceId, sessionId)) return false;
        return db.insert(studentName, deviceId, sessionId);
    }

    @Override
    public boolean isAlreadyMarked(String deviceId, String sessionId) {
        return db.exists(deviceId, sessionId);
    }

    @Override
    public List<String> getAttendance(String sessionId) {
        return db.queryBySession(sessionId);
    }

    @Override
    public int getAttendanceCount(String sessionId) {
        return db.countBySession(sessionId);
    }
}
