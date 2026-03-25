package com.example.digitalsphere.data.db;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.util.List;
import static org.junit.Assert.*;

/**
 * ─────────────────────────────────────────────────────────────────────────
 *  INTEGRATION TEST — AttendanceDB (raw SQLite layer)
 *  Runs on a real Android device / emulator with a real SQLite file.
 *  Lives in package com.example.digitalsphere.data.db to access
 *  the package-private insert / exists / queryBySession / countBySession.
 * ─────────────────────────────────────────────────────────────────────────
 *
 * USER STORIES
 * ────────────
 * US-IDB-01  As a developer, a record inserted into AttendanceDB can be
 *            read back — basic Android SQLite CRUD works on real storage.
 *
 * US-IDB-02  As a developer, inserting the same (device_id, session_id)
 *            pair twice uses CONFLICT_IGNORE: the second call returns false
 *            and no exception is thrown — the app never crashes on retry.
 *
 * US-IDB-03  As a professor, after N students are recorded, countBySession
 *            returns exactly N.
 *
 * US-IDB-04  As a professor running two sessions, records from session A
 *            never appear in session B's query — full session isolation.
 *
 * US-IDB-05  As a system, the same device can attend two different sessions:
 *            the UNIQUE INDEX is scoped to (device_id, session_id), not
 *            just device_id.
 *
 * US-IDB-06  As a developer, the database upgrade path (drop + recreate)
 *            runs without crashing and leaves the table in a clean state.
 */
@RunWith(AndroidJUnit4.class)
public class AttendanceDBIntegrationTest {

    private static final String TEST_DB = "test_attendance.db";

    private AttendanceDB db;
    private Context      ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.deleteDatabase(TEST_DB);

        // Use package-private constructor that accepts a custom DB name
        // so this test never touches the production "attendance.db" file.
        db = new AttendanceDB(ctx, TEST_DB);
    }

    @After
    public void tearDown() {
        db.close();
        ctx.deleteDatabase(TEST_DB);
    }

    // ── US-IDB-01  Basic insert + exists ──────────────────────────────────

    @Test
    public void insert_newRecord_returnsTrue() {
        // US-IDB-01
        boolean result = db.insert("Yash Sharma", "device_001", "cs101");
        assertTrue(result);
    }

    @Test
    public void insert_newRecord_existsReturnsTrue() {
        db.insert("Yash Sharma", "device_001", "cs101");
        assertTrue(db.exists("device_001", "cs101"));
    }

    @Test
    public void exists_beforeInsert_returnsFalse() {
        // US-IDB-01: nothing in the DB yet
        assertFalse(db.exists("device_999", "cs101"));
    }

    // ── US-IDB-02  Duplicate CONFLICT_IGNORE ──────────────────────────────

    @Test
    public void insert_duplicate_returnsFalse_noException() {
        // US-IDB-02
        db.insert("Yash Sharma", "device_001", "cs101");
        boolean second = db.insert("Yash Sharma", "device_001", "cs101");
        assertFalse(second);
    }

    @Test
    public void insert_duplicate_countRemainsOne() {
        // US-IDB-02: count must not grow
        db.insert("Yash Sharma", "device_001", "cs101");
        db.insert("Yash Sharma", "device_001", "cs101");
        assertEquals(1, db.countBySession("cs101"));
    }

    @Test
    public void insert_duplicate_queryReturnsOneRecord() {
        db.insert("Yash Sharma", "device_001", "cs101");
        db.insert("Yash Sharma", "device_001", "cs101");
        assertEquals(1, db.queryBySession("cs101").size());
    }

    // ── US-IDB-03  Count ──────────────────────────────────────────────────

    @Test
    public void countBySession_threeStudents_returnsThree() {
        // US-IDB-03
        db.insert("Yash",  "dev_001", "cs101");
        db.insert("Priya", "dev_002", "cs101");
        db.insert("Rahul", "dev_003", "cs101");
        assertEquals(3, db.countBySession("cs101"));
    }

    @Test
    public void countBySession_emptySession_returnsZero() {
        assertEquals(0, db.countBySession("cs101"));
    }

    // ── US-IDB-04  Session isolation ──────────────────────────────────────

    @Test
    public void queryBySession_sessionA_doesNotReturnSessionBRecords() {
        // US-IDB-04
        db.insert("Yash",  "dev_001", "cs101");
        db.insert("Priya", "dev_002", "math");

        List<String> csRecords   = db.queryBySession("cs101");
        List<String> mathRecords = db.queryBySession("math");

        assertEquals(1, csRecords.size());
        assertEquals(1, mathRecords.size());
        assertTrue(csRecords.get(0).contains("Yash"));
        assertTrue(mathRecords.get(0).contains("Priya"));
    }

    @Test
    public void countBySession_isolatedSessions_correctCounts() {
        db.insert("Yash",  "dev_001", "cs101");
        db.insert("Priya", "dev_002", "cs101");
        db.insert("Rahul", "dev_003", "math");

        assertEquals(2, db.countBySession("cs101"));
        assertEquals(1, db.countBySession("math"));
    }

    // ── US-IDB-05  Same device, two sessions ──────────────────────────────

    @Test
    public void insert_sameDevice_differentSessions_bothAllowed() {
        // US-IDB-05: UNIQUE is (device_id, session_id) — not just device_id
        boolean first  = db.insert("Yash", "dev_001", "cs101");
        boolean second = db.insert("Yash", "dev_001", "math");

        assertTrue(first);
        assertTrue(second);
        assertEquals(1, db.countBySession("cs101"));
        assertEquals(1, db.countBySession("math"));
    }

    // ── Record format ─────────────────────────────────────────────────────

    @Test
    public void queryBySession_record_containsStudentName() {
        db.insert("Priya Patel", "dev_002", "cs101");
        List<String> records = db.queryBySession("cs101");

        assertFalse(records.isEmpty());
        assertTrue("Record should contain student name",
                records.get(0).contains("Priya Patel"));
    }

    @Test
    public void queryBySession_record_hasRowNumber() {
        db.insert("Yash",  "dev_001", "cs101");
        db.insert("Priya", "dev_002", "cs101");

        List<String> records = db.queryBySession("cs101");
        assertTrue(records.get(0).startsWith("1."));
        assertTrue(records.get(1).startsWith("2."));
    }

    @Test
    public void queryBySession_orderedByInsertionOrder() {
        db.insert("Yash",  "dev_001", "cs101");
        db.insert("Priya", "dev_002", "cs101");
        db.insert("Rahul", "dev_003", "cs101");

        List<String> records = db.queryBySession("cs101");
        assertTrue(records.get(0).contains("Yash"));
        assertTrue(records.get(1).contains("Priya"));
        assertTrue(records.get(2).contains("Rahul"));
    }

    // ── US-IDB-06  DB upgrade ─────────────────────────────────────────────

    @Test
    public void onUpgrade_doesNotCrash_andTableIsUsable() {
        // US-IDB-06: simulate upgrade by calling onUpgrade directly
        db.onUpgrade(db.getWritableDatabase(), 1, 2);

        // After upgrade, table should be empty but usable
        assertEquals(0, db.countBySession("cs101"));
        assertTrue(db.insert("Yash", "dev_001", "cs101"));
    }
}
