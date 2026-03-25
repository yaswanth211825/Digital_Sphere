package com.example.digitalsphere.helper;

import com.example.digitalsphere.domain.IAttendanceRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * In-memory IAttendanceRepository for unit tests.
 * No Android context, no SQLite — pure Java HashMap.
 */
public class FakeAttendanceRepository implements IAttendanceRepository {

    /** sessionId → set of deviceIds already marked */
    private final Map<String, Set<String>>  marked  = new HashMap<>();
    /** sessionId → ordered list of formatted display strings */
    private final Map<String, List<String>> records = new HashMap<>();

    @Override
    public boolean markPresent(String studentName, String deviceId, String sessionId) {
        Set<String> devices = marked.computeIfAbsent(sessionId, k -> new HashSet<>());
        if (!devices.add(deviceId)) return false;           // already present

        List<String> list = records.computeIfAbsent(sessionId, k -> new ArrayList<>());
        int idx = list.size() + 1;
        list.add(idx + ". " + studentName + "  •  test-timestamp");
        return true;
    }

    @Override
    public boolean isAlreadyMarked(String deviceId, String sessionId) {
        Set<String> devices = marked.get(sessionId);
        return devices != null && devices.contains(deviceId);
    }

    @Override
    public List<String> getAttendance(String sessionId) {
        return records.getOrDefault(sessionId, new ArrayList<>());
    }

    @Override
    public int getAttendanceCount(String sessionId) {
        return getAttendance(sessionId).size();
    }

    /** Test helper: total number of unique records across all sessions. */
    public int totalRecords() {
        return records.values().stream().mapToInt(List::size).sum();
    }
}
