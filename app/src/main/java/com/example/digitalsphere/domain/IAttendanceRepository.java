package com.example.digitalsphere.domain;

import java.util.List;

/**
 * Data contract for attendance storage.
 * Presenters depend on this interface — NOT on SQLite directly.
 * This allows unit tests to use a fake (in-memory) implementation.
 *
 * SRS references: FR-22, FR-23, FR-25, FR-26, FR-27, NFR-07, NFR-13
 */
public interface IAttendanceRepository {

    /**
     * Records a student as present for a session.
     * @return true if inserted, false if this device already marked for this session.
     */
    boolean markPresent(String studentName, String deviceId, String sessionId);

    /**
     * Returns true if this device has already marked attendance for this session.
     * Used as a security gate — called before markPresent to avoid DB round-trip on duplicates.
     */
    boolean isAlreadyMarked(String deviceId, String sessionId);

    /**
     * Returns all attendance records for a session, formatted for display.
     * Example: ["1. Yash  •  25 Mar 2026, 03:30 PM", "2. Priya  •  ..."]
     */
    List<String> getAttendance(String sessionId);

    /**
     * Returns the count of students marked present for a session.
     */
    int getAttendanceCount(String sessionId);
}
