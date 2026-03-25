package com.example.digitalsphere;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.digitalsphere.data.db.AttendanceRepository;
import com.example.digitalsphere.domain.IAttendanceRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import static org.junit.Assert.*;

/**
 * ─────────────────────────────────────────────────────────────────────────
 *  INTEGRATION TEST — AttendanceRepository
 *  Tests the repository contract (IAttendanceRepository) backed by real
 *  SQLite on the device. Presenters call these methods, so correctness
 *  here guarantees the whole data layer behaves correctly.
 * ─────────────────────────────────────────────────────────────────────────
 *
 * USER STORIES
 * ────────────
 * US-IREP-01  As a presenter, isAlreadyMarked() returns false for a device
 *             that has not yet attended, and true after markPresent() —
 *             so the presenter can gate duplicate writes correctly.
 *
 * US-IREP-02  As a presenter, markPresent() returns true on first call and
 *             false on a repeat for the same (device, session) pair —
 *             no double-count ever reaches the DB.
 *
 * US-IREP-03  As a professor, getAttendance() returns a non-empty list
 *             with student names visible — the display format is correct.
 *
 * US-IREP-04  As a professor, getAttendanceCount() always matches
 *             the size of the list returned by getAttendance().
 *
 * US-IREP-05  As a professor, getAttendance() for an empty / unknown
 *             session returns an empty list — no crash, no nulls.
 *
 * US-IREP-06  As a professor running two sessions, each session's
 *             attendance list is completely independent.
 */
@RunWith(AndroidJUnit4.class)
public class AttendanceRepositoryIntegrationTest {

    private static final String SESSION_CS  = "cs101";
    private static final String SESSION_MATH = "math";

    private IAttendanceRepository repo;
    private Context               ctx;

    @Before
    public void setUp() {
        ctx  = ApplicationProvider.getApplicationContext();
        ctx.deleteDatabase("attendance.db");    // fresh DB for every test
        repo = new AttendanceRepository(ctx);
    }

    @After
    public void tearDown() {
        ctx.deleteDatabase("attendance.db");
    }

    // ── US-IREP-01  isAlreadyMarked gate ──────────────────────────────────

    @Test
    public void isAlreadyMarked_beforeMark_returnsFalse() {
        // US-IREP-01
        assertFalse(repo.isAlreadyMarked("dev_001", SESSION_CS));
    }

    @Test
    public void isAlreadyMarked_afterMarkPresent_returnsTrue() {
        // US-IREP-01
        repo.markPresent("Yash Sharma", "dev_001", SESSION_CS);
        assertTrue(repo.isAlreadyMarked("dev_001", SESSION_CS));
    }

    @Test
    public void isAlreadyMarked_differentSession_returnsFalse() {
        repo.markPresent("Yash Sharma", "dev_001", SESSION_CS);
        assertFalse(repo.isAlreadyMarked("dev_001", SESSION_MATH));
    }

    // ── US-IREP-02  markPresent idempotency ───────────────────────────────

    @Test
    public void markPresent_firstCall_returnsTrue() {
        // US-IREP-02
        assertTrue(repo.markPresent("Yash", "dev_001", SESSION_CS));
    }

    @Test
    public void markPresent_secondCall_returnsFalse() {
        // US-IREP-02
        repo.markPresent("Yash", "dev_001", SESSION_CS);
        assertFalse(repo.markPresent("Yash", "dev_001", SESSION_CS));
    }

    @Test
    public void markPresent_doesNotThrow_onDuplicate() {
        // No exception should be thrown on duplicate (CONFLICT_IGNORE)
        try {
            repo.markPresent("Yash", "dev_001", SESSION_CS);
            repo.markPresent("Yash", "dev_001", SESSION_CS);
        } catch (Exception e) {
            fail("markPresent threw an exception on duplicate: " + e.getMessage());
        }
    }

    // ── US-IREP-03  getAttendance display format ──────────────────────────

    @Test
    public void getAttendance_singleRecord_containsStudentName() {
        // US-IREP-03
        repo.markPresent("Priya Patel", "dev_002", SESSION_CS);
        List<String> list = repo.getAttendance(SESSION_CS);

        assertFalse(list.isEmpty());
        assertTrue("List entry should contain student name",
                list.get(0).contains("Priya Patel"));
    }

    @Test
    public void getAttendance_recordHasRowNumber() {
        repo.markPresent("Yash",  "dev_001", SESSION_CS);
        repo.markPresent("Priya", "dev_002", SESSION_CS);

        List<String> list = repo.getAttendance(SESSION_CS);
        assertTrue(list.get(0).startsWith("1."));
        assertTrue(list.get(1).startsWith("2."));
    }

    @Test
    public void getAttendance_recordsAreInInsertionOrder() {
        repo.markPresent("Yash",  "dev_001", SESSION_CS);
        repo.markPresent("Priya", "dev_002", SESSION_CS);
        repo.markPresent("Rahul", "dev_003", SESSION_CS);

        List<String> list = repo.getAttendance(SESSION_CS);
        assertTrue(list.get(0).contains("Yash"));
        assertTrue(list.get(1).contains("Priya"));
        assertTrue(list.get(2).contains("Rahul"));
    }

    // ── US-IREP-04  getAttendanceCount consistency ─────────────────────────

    @Test
    public void getAttendanceCount_matchesListSize() {
        // US-IREP-04
        repo.markPresent("Yash",  "dev_001", SESSION_CS);
        repo.markPresent("Priya", "dev_002", SESSION_CS);

        int count = repo.getAttendanceCount(SESSION_CS);
        int size  = repo.getAttendance(SESSION_CS).size();
        assertEquals(count, size);
    }

    @Test
    public void getAttendanceCount_afterDuplicate_remainsOne() {
        repo.markPresent("Yash", "dev_001", SESSION_CS);
        repo.markPresent("Yash", "dev_001", SESSION_CS);
        assertEquals(1, repo.getAttendanceCount(SESSION_CS));
    }

    // ── US-IREP-05  Empty / unknown session ───────────────────────────────

    @Test
    public void getAttendance_unknownSession_returnsEmptyList() {
        // US-IREP-05
        List<String> list = repo.getAttendance("unknown_session");
        assertNotNull(list);
        assertTrue(list.isEmpty());
    }

    @Test
    public void getAttendanceCount_unknownSession_returnsZero() {
        assertEquals(0, repo.getAttendanceCount("unknown_session"));
    }

    // ── US-IREP-06  Session isolation ─────────────────────────────────────

    @Test
    public void getAttendance_twoSessions_dataIsIsolated() {
        // US-IREP-06
        repo.markPresent("Yash",  "dev_001", SESSION_CS);
        repo.markPresent("Priya", "dev_002", SESSION_MATH);

        assertEquals(1, repo.getAttendance(SESSION_CS).size());
        assertEquals(1, repo.getAttendance(SESSION_MATH).size());

        assertTrue(repo.getAttendance(SESSION_CS).get(0).contains("Yash"));
        assertTrue(repo.getAttendance(SESSION_MATH).get(0).contains("Priya"));
    }

    @Test
    public void markPresent_sameDevice_differentSessions_bothSucceed() {
        // Same student attending two separate classes
        assertTrue(repo.markPresent("Yash", "dev_001", SESSION_CS));
        assertTrue(repo.markPresent("Yash", "dev_001", SESSION_MATH));
    }
}
