package com.example.digitalsphere;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.digitalsphere.data.db.AttendanceRepository;
import com.example.digitalsphere.helper.FakeBleManager;
import com.example.digitalsphere.helper.FakeStudentView;
import com.example.digitalsphere.presenter.StudentPresenter;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * ─────────────────────────────────────────────────────────────────────────
 *  INTEGRATION TEST — StudentPresenter + real AttendanceRepository
 *
 *  BLE callbacks are simulated. SQLite is real.
 *  This proves the full student-side path:
 *     in-range signal → Presenter logic → real SQLite write → View confirmation
 * ─────────────────────────────────────────────────────────────────────────
 *
 * USER STORIES
 * ────────────
 * US-ISP-01  As a student, when my phone comes in range of the professor's
 *            beacon (simulated in-range signal), my attendance is written
 *            to the real SQLite DB and the view shows a confirmation.
 *
 * US-ISP-02  As a student, if I was already marked in a previous app session
 *            (record exists in DB), the presenter detects this via
 *            isAlreadyMarked() and shows "already marked" — no duplicate.
 *
 * US-ISP-03  As a student, coming back into range multiple times in the
 *            same scan session writes only one DB record — idempotent.
 *
 * US-ISP-04  As a student, my attendance record is stored under the correct
 *            normalised session ID ("CS 101" → "cs101").
 *
 * US-ISP-05  As a student, out-of-range signals never create DB records —
 *            only being close to the professor counts.
 *
 * US-ISP-06  As a student, after stopping and restarting the scan without
 *            closing the app, a subsequent in-range signal still marks
 *            attendance correctly.
 */
@RunWith(AndroidJUnit4.class)
public class StudentPresenterIntegrationTest {

    private static final int IN_RANGE  = -75;   // at threshold
    private static final int OUT_RANGE = -76;   // below threshold

    private StudentPresenter    presenter;
    private FakeStudentView     view;
    private FakeBleManager      ble;
    private AttendanceRepository repo;
    private Context              ctx;

    @Before
    public void setUp() {
        ctx  = ApplicationProvider.getApplicationContext();
        ctx.deleteDatabase("attendance.db");

        view = new FakeStudentView();
        ble  = new FakeBleManager();
        repo = new AttendanceRepository(ctx);

        presenter = new StudentPresenter(repo, ble);
        presenter.attach(view);
    }

    @After
    public void tearDown() {
        presenter.detach();
        ctx.deleteDatabase("attendance.db");
    }

    // ── US-ISP-01  In-range → DB write + view confirmation ────────────────

    @Test
    public void inRangeSignal_realDbHasRecord() {
        // US-ISP-01
        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(IN_RANGE, true);

        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    @Test
    public void inRangeSignal_viewConfirmationFired() {
        // US-ISP-01 (FR-51)
        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(IN_RANGE, true);

        assertTrue(view.attendanceMarkedFired);
    }

    @Test
    public void inRangeSignal_presenterMarkedPresentIsTrue() {
        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(IN_RANGE, true);

        assertTrue(presenter.isMarkedPresent());
    }

    // ── US-ISP-02  Already in DB → alreadyMarked shown, no duplicate ──────

    @Test
    public void alreadyInDb_alreadyMarkedViewFired() {
        // US-ISP-02: simulate a previous session's record already in DB
        repo.markPresent("Yash Sharma", "beacon_yash_sharma_cs101", "cs101");

        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(IN_RANGE, true);

        assertTrue(view.alreadyMarkedFired);
        assertFalse(view.attendanceMarkedFired);
    }

    @Test
    public void alreadyInDb_countRemainsOne() {
        // US-ISP-02: no new record should be written
        repo.markPresent("Yash Sharma", "beacon_yash_sharma_cs101", "cs101");

        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(IN_RANGE, true);

        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    // ── US-ISP-03  Multiple in-range signals → one DB record ──────────────

    @Test
    public void inRange_thrice_singleDbRecord() {
        // US-ISP-03
        presenter.startScan("Yash", "CS101");
        ble.simulateSignalUpdate(IN_RANGE, true);
        ble.simulateSignalUpdate(IN_RANGE, true);
        ble.simulateSignalUpdate(IN_RANGE, true);

        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    // ── US-ISP-04  Session ID normalisation ───────────────────────────────

    @Test
    public void sessionIdNormalised_dbRecordUnderNormalisedId() {
        // US-ISP-04: "CS 101" → stored under "cs101"
        presenter.startScan("Yash", "CS 101");
        ble.simulateSignalUpdate(IN_RANGE, true);

        assertEquals(1, repo.getAttendanceCount("cs101"));
        assertEquals(0, repo.getAttendanceCount("CS 101"));   // original key has nothing
    }

    // ── US-ISP-05  Out-of-range → no DB record ────────────────────────────

    @Test
    public void outOfRangeSignal_noDbRecord() {
        // US-ISP-05
        presenter.startScan("Yash", "CS101");
        ble.simulateSignalUpdate(OUT_RANGE, false);

        assertEquals(0, repo.getAttendanceCount("cs101"));
    }

    @Test
    public void mixedSignals_onlyCountsWhenInRange() {
        // out → in → out  — should produce exactly one record
        presenter.startScan("Yash", "CS101");
        ble.simulateSignalUpdate(OUT_RANGE, false);
        ble.simulateSignalUpdate(IN_RANGE,  true);
        ble.simulateSignalUpdate(OUT_RANGE, false);

        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    // ── US-ISP-06  Stop + restart scan still works ────────────────────────

    @Test
    public void stopThenRestartScan_attendanceMarkedOnSecondRun() {
        // US-ISP-06: first run — don't go in range
        presenter.startScan("Yash", "CS101");
        ble.simulateSignalUpdate(OUT_RANGE, false);
        presenter.stopScan();

        // Second run — now go in range
        presenter.startScan("Yash", "CS101");
        ble.simulateSignalUpdate(IN_RANGE, true);

        assertTrue(view.attendanceMarkedFired);
        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    // ── DB record format ──────────────────────────────────────────────────

    @Test
    public void attendanceRecord_containsStudentName() {
        presenter.startScan("Priya Patel", "CS101");
        ble.simulateSignalUpdate(IN_RANGE, true);

        assertFalse(repo.getAttendance("cs101").isEmpty());
        assertTrue(repo.getAttendance("cs101").get(0).contains("Priya Patel"));
    }
}
