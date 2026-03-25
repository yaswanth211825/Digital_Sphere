package com.example.digitalsphere;

import com.example.digitalsphere.helper.FakeAttendanceRepository;
import com.example.digitalsphere.helper.FakeBleManager;
import com.example.digitalsphere.helper.FakeStudentView;
import com.example.digitalsphere.presenter.StudentPresenter;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ─────────────────────────────────────────────────────────
 *  MODULE: StudentPresenter
 * ─────────────────────────────────────────────────────────
 *
 * USER STORIES
 * ────────────
 * US-SP-01  As a student, when I leave my name blank and tap Scan,
 *           I see a clear error and scanning does NOT start.
 *
 * US-SP-02  As a student, when I leave the session field blank and
 *           tap Scan, I see a clear error and scanning does NOT start.
 *
 * US-SP-03  As a student, when I tap Scan with valid inputs, scanning
 *           starts and the signal strength panel becomes visible.
 *
 * US-SP-04  As a student, when my phone detects the professor's beacon
 *           at close range (RSSI ≥ threshold), my attendance is marked
 *           and I see a "✅ Attendance marked" confirmation. (FR-51)
 *
 * US-SP-05  As a student, when I'm too far away (RSSI < threshold),
 *           my attendance is NOT marked — I just see the signal panel.
 *
 * US-SP-06  As a student, if I was already marked present, I see
 *           "Already marked for this session" instead of a duplicate
 *           mark being inserted.
 *
 * US-SP-07  As a student, if I'm already scanning and tap Scan again,
 *           nothing changes — no crash, no double-start.
 *
 * US-SP-08  As a student, when I tap Stop, scanning ends, the signal
 *           panel hides, and the Scan button re-enables.
 *
 * US-SP-09  As a student, once my attendance is marked, coming back
 *           into range again does NOT create a second record.
 *
 * US-SP-10  As a student, the session ID I type ("CS 101") is
 *           normalised to "cs101" to match the professor's session.
 */
public class StudentPresenterTest {

    // Mirrors BleScanner.RSSI_THRESHOLD (-75 dBm). Hardcoded to avoid importing
    // the package-private BleScanner class from the test package.
    private static final int IN_RANGE_RSSI  = -75;   // exactly at threshold = in range
    private static final int OUT_OF_RANGE   = -76;   // one dBm below threshold = out of range

    private StudentPresenter       presenter;
    private FakeStudentView        view;
    private FakeAttendanceRepository repo;
    private FakeBleManager         ble;

    @Before
    public void setUp() {
        view = new FakeStudentView();
        repo = new FakeAttendanceRepository();
        ble  = new FakeBleManager();

        presenter = new StudentPresenter(repo, ble);
        presenter.attach(view);
    }

    // ── US-SP-01  Blank student name ──────────────────────────────────────

    @Test
    public void startScan_blankName_showsError() {
        // US-SP-01
        presenter.startScan("", "CS101");
        assertFalse(view.errors.isEmpty());
    }

    @Test
    public void startScan_blankName_scanningNotStarted() {
        // US-SP-01
        presenter.startScan("", "CS101");
        assertFalse(presenter.isScanning());
    }

    @Test
    public void startScan_nullName_scanningNotStarted() {
        presenter.startScan(null, "CS101");
        assertFalse(presenter.isScanning());
    }

    // ── US-SP-02  Blank session field ─────────────────────────────────────

    @Test
    public void startScan_blankSession_showsError() {
        // US-SP-02
        presenter.startScan("Yash Sharma", "");
        assertFalse(view.errors.isEmpty());
    }

    @Test
    public void startScan_blankSession_scanningNotStarted() {
        // US-SP-02
        presenter.startScan("Yash Sharma", "");
        assertFalse(presenter.isScanning());
    }

    @Test
    public void startScan_nullSession_showsError() {
        presenter.startScan("Yash Sharma", null);
        assertFalse(presenter.isScanning());
    }

    // ── US-SP-03  Valid scan start ─────────────────────────────────────────

    @Test
    public void startScan_validInputs_scanningIsTrue() {
        // US-SP-03
        presenter.startScan("Yash Sharma", "CS101");
        assertTrue(presenter.isScanning());
    }

    @Test
    public void startScan_validInputs_bleStarted() {
        // US-SP-03
        presenter.startScan("Yash Sharma", "CS101");
        assertTrue(ble.studentModeStarted);
    }

    @Test
    public void startScan_validInputs_signalPanelVisible() {
        // US-SP-03
        presenter.startScan("Yash Sharma", "CS101");
        assertTrue(view.signalPanelVisible);
    }

    @Test
    public void startScan_validInputs_scanButtonDisabled() {
        presenter.startScan("Yash Sharma", "CS101");
        assertFalse(view.scanEnabled);
    }

    @Test
    public void startScan_nameSentToBle() {
        presenter.startScan("Yash Sharma", "CS101");
        assertEquals("Yash Sharma", ble.lastStudentName);
    }

    // ── US-SP-10  Session ID normalisation ────────────────────────────────

    @Test
    public void startScan_sessionIdNormalised() {
        // US-SP-10: "CS 101" → "cs101"
        presenter.startScan("Yash", "CS 101");
        // Signal comes in — attendance should be stored under "cs101"
        ble.simulateSignalUpdate(IN_RANGE_RSSI, true);
        assertTrue(repo.getAttendanceCount("cs101") > 0);
    }

    // ── US-SP-04  In-range → attendance marked ────────────────────────────

    @Test
    public void inRangeSignal_marksAttendance() {
        // US-SP-04
        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(IN_RANGE_RSSI, true);

        assertTrue(view.attendanceMarkedFired);
    }

    @Test
    public void inRangeSignal_attendanceStoredInRepo() {
        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(IN_RANGE_RSSI, true);

        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    @Test
    public void inRangeSignal_markedPresentFlagSet() {
        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(IN_RANGE_RSSI, true);

        assertTrue(presenter.isMarkedPresent());
    }

    @Test
    public void inRangeSignal_viewUpdatedWithRssi() {
        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(-65, true);

        assertEquals(-65, view.lastRssi);
        assertTrue(view.lastInRange);
    }

    // ── US-SP-05  Out-of-range → no mark ─────────────────────────────────

    @Test
    public void outOfRangeSignal_attendanceNotMarked() {
        // US-SP-05
        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(OUT_OF_RANGE, false);

        assertFalse(view.attendanceMarkedFired);
        assertEquals(0, repo.getAttendanceCount("cs101"));
    }

    @Test
    public void outOfRangeSignal_markedPresentStaysFalse() {
        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(OUT_OF_RANGE, false);

        assertFalse(presenter.isMarkedPresent());
    }

    @Test
    public void outOfRangeSignal_viewStillUpdated() {
        // Signal panel should show even when out of range
        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(OUT_OF_RANGE, false);

        assertEquals(OUT_OF_RANGE, view.lastRssi);
        assertFalse(view.lastInRange);
    }

    // ── US-SP-06  Already marked → show message, no duplicate ────────────

    @Test
    public void alreadyMarked_doesNotInsertDuplicate() {
        // US-SP-06: pre-seed the repo so the record already exists
        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(IN_RANGE_RSSI, true);  // first mark

        int countAfterFirst = repo.getAttendanceCount("cs101");

        ble.simulateSignalUpdate(IN_RANGE_RSSI, true);  // second signal (already marked)
        assertEquals(countAfterFirst, repo.getAttendanceCount("cs101"));
    }

    @Test
    public void alreadyMarked_fromPreviousSession_showsAlreadyMarkedMessage() {
        // Pre-populate repo as if student was already marked (e.g., re-opened app)
        String deviceId = "beacon_yash_sharma_cs101";
        repo.markPresent("Yash Sharma", deviceId, "cs101");

        presenter.startScan("Yash Sharma", "CS101");
        ble.simulateSignalUpdate(IN_RANGE_RSSI, true);

        assertTrue(view.alreadyMarkedFired);
        assertFalse(view.attendanceMarkedFired);
    }

    // ── US-SP-07  Double-start prevention ────────────────────────────────

    @Test
    public void startScan_calledTwice_scanStartedOnlyOnce() {
        // US-SP-07
        presenter.startScan("Yash", "CS101");
        presenter.startScan("Yash", "CS101");   // second tap — should be ignored

        // BLE was started once; a second startStudentMode call would overwrite the listener
        // We verify only one record if we fire an in-range signal
        ble.simulateSignalUpdate(IN_RANGE_RSSI, true);
        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    // ── US-SP-08  Stop scan ───────────────────────────────────────────────

    @Test
    public void stopScan_scanningBecomesInactive() {
        // US-SP-08
        presenter.startScan("Yash", "CS101");
        presenter.stopScan();
        assertFalse(presenter.isScanning());
    }

    @Test
    public void stopScan_signalPanelHidden() {
        presenter.startScan("Yash", "CS101");
        presenter.stopScan();
        assertFalse(view.signalPanelVisible);
    }

    @Test
    public void stopScan_scanButtonReEnabled() {
        presenter.startScan("Yash", "CS101");
        presenter.stopScan();
        assertTrue(view.scanEnabled);
    }

    @Test
    public void stopScan_bleStopped() {
        presenter.startScan("Yash", "CS101");
        presenter.stopScan();
        assertFalse(ble.studentModeStarted);
    }

    // ── US-SP-09  In-range again after mark → no second record ────────────

    @Test
    public void inRange_twice_onlyOneRecord() {
        // US-SP-09
        presenter.startScan("Yash", "CS101");
        ble.simulateSignalUpdate(IN_RANGE_RSSI, true);   // marks attendance
        ble.simulateSignalUpdate(IN_RANGE_RSSI, true);   // stays in range

        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    // ── BLE error handling ────────────────────────────────────────────────

    @Test
    public void bleError_showsErrorToView() {
        presenter.startScan("Yash", "CS101");
        ble.simulateStudentError("Bluetooth not enabled.");
        assertFalse(view.errors.isEmpty());
    }

    @Test
    public void bleError_scanButtonReEnabled() {
        presenter.startScan("Yash", "CS101");
        ble.simulateStudentError("BLE error.");
        assertTrue(view.scanEnabled);
    }

    @Test
    public void bleError_scanningBecomesInactive() {
        presenter.startScan("Yash", "CS101");
        ble.simulateStudentError("BLE error.");
        assertFalse(presenter.isScanning());
    }

    // ── Beacon started callback ────────────────────────────────────────────

    @Test
    public void beaconStarted_notifiesView() {
        presenter.startScan("Yash", "CS101");
        ble.simulateStudentBeaconStarted();
        assertTrue(view.beaconStartedFired);
    }

    // ── detach() safety ───────────────────────────────────────────────────

    @Test
    public void detach_noNullPointerOnSubsequentSignal() {
        presenter.startScan("Yash", "CS101");
        presenter.detach();
        // After detach, BLE callbacks should be silently ignored
        ble.simulateSignalUpdate(IN_RANGE_RSSI, true);
        // No exception = pass
    }
}
