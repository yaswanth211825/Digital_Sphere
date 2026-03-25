package com.example.digitalsphere;

import com.example.digitalsphere.helper.FakeAttendanceRepository;
import com.example.digitalsphere.helper.FakeBleManager;
import com.example.digitalsphere.helper.FakeProfessorView;
import com.example.digitalsphere.presenter.ProfessorPresenter;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ─────────────────────────────────────────────────────────
 *  MODULE: ProfessorPresenter
 * ─────────────────────────────────────────────────────────
 *
 * USER STORIES
 * ────────────
 * US-PP-01  As a professor, when I enter a valid session name "CS101"
 *           and click Start, the session becomes active and the derived
 *           session ID ("cs101") is shown so I can tell students.
 *
 * US-PP-02  As a professor, when I leave the session name blank and
 *           click Start, I see a clear error and the session does NOT start.
 *
 * US-PP-03  As a professor, when I type "xyz" for duration, I see an
 *           error and the session does NOT start.
 *
 * US-PP-04  As a professor, when a student is detected via BLE, their
 *           name appears in the attendance list and the count increases.
 *
 * US-PP-05  As a professor, if a student arrives AFTER I have stopped
 *           the session, their name is NOT added to the list. (FR-13)
 *
 * US-PP-06  As a professor, if the same student is detected twice
 *           (BLE fires twice), they appear only once in the list.
 *
 * US-PP-07  As a professor, when I click Stop, the session ends,
 *           the Start button re-enables, and attendance refreshes.
 *
 * US-PP-08  As a professor, when BLE fails to start, I see an error
 *           and the Start button re-enables so I can try again.
 *
 * US-PP-09  As a professor, starting a session with an empty duration
 *           field uses the default (5 min) without showing an error.
 *
 * US-PP-10  As a professor, the session ID is derived from the name —
 *           "CS 101" → "cs101" — matching what students enter.
 */
public class ProfessorPresenterTest {

    // System under test
    private ProfessorPresenter presenter;

    // Injected fakes
    private FakeProfessorView        view;
    private FakeAttendanceRepository repo;
    private FakeBleManager           ble;

    @Before
    public void setUp() {
        view = new FakeProfessorView();
        repo = new FakeAttendanceRepository();
        ble  = new FakeBleManager();

        // Anonymous subclass overrides startTimer() as a no-op:
        // CountDownTimer needs an Android Looper, which doesn't exist on JVM.
        presenter = new ProfessorPresenter(repo, ble) {
            @Override protected void startTimer(int minutes) { /* no-op */ }
        };
        presenter.attach(view);
    }

    // ── US-PP-01  Valid session start ──────────────────────────────────────

    @Test
    public void startSession_validName_sessionBecomesActive() {
        // US-PP-01
        presenter.startSession("CS101", "5");
        assertTrue(presenter.isSessionActive());
    }

    @Test
    public void startSession_validName_sessionIdShownToView() {
        // US-PP-01 + US-PP-10: "CS 101" → "cs101"
        presenter.startSession("CS 101", "5");
        assertEquals("cs101", view.shownSessionId);
    }

    @Test
    public void startSession_validName_startButtonDisabled() {
        presenter.startSession("CS101", "5");
        assertFalse(view.startEnabled);
    }

    @Test
    public void startSession_validName_loadingShown() {
        presenter.startSession("CS101", "5");
        assertTrue(view.loading);
    }

    @Test
    public void startSession_validName_bleStarted() {
        presenter.startSession("CS101", "5");
        assertTrue(ble.professorModeStarted);
    }

    // ── US-PP-02  Blank session name ───────────────────────────────────────

    @Test
    public void startSession_blankName_showsError() {
        // US-PP-02
        presenter.startSession("", "5");
        assertNotNull(view.lastError);
        assertFalse(view.lastError.isEmpty());
    }

    @Test
    public void startSession_blankName_sessionNotStarted() {
        // US-PP-02
        presenter.startSession("", "5");
        assertFalse(presenter.isSessionActive());
    }

    @Test
    public void startSession_blankName_bleNotStarted() {
        presenter.startSession("", "5");
        assertFalse(ble.professorModeStarted);
    }

    @Test
    public void startSession_nullName_showsError() {
        presenter.startSession(null, "5");
        assertFalse(presenter.isSessionActive());
    }

    @Test
    public void startSession_singleCharName_showsError() {
        presenter.startSession("A", "5");
        assertFalse(presenter.isSessionActive());
    }

    // ── US-PP-03  Invalid duration ─────────────────────────────────────────

    @Test
    public void startSession_nonNumericDuration_showsError() {
        // US-PP-03
        presenter.startSession("CS101", "xyz");
        assertFalse(view.errors.isEmpty());
    }

    @Test
    public void startSession_nonNumericDuration_sessionNotStarted() {
        presenter.startSession("CS101", "abc");
        assertFalse(presenter.isSessionActive());
    }

    @Test
    public void startSession_zeroDuration_showsError() {
        presenter.startSession("CS101", "0");
        assertFalse(presenter.isSessionActive());
    }

    @Test
    public void startSession_negativeDuration_showsError() {
        presenter.startSession("CS101", "-1");
        assertFalse(presenter.isSessionActive());
    }

    // ── US-PP-09  Blank duration → default ────────────────────────────────

    @Test
    public void startSession_blankDuration_sessionsStartsWithDefault() {
        // US-PP-09: no error, session starts normally
        presenter.startSession("CS101", "");
        assertTrue(presenter.isSessionActive());
        assertTrue(view.errors.isEmpty());
    }

    // ── US-PP-04  Student detected → attendance updated ────────────────────

    @Test
    public void studentDetected_duringActiveSession_appearsInList() {
        // US-PP-04
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");

        assertFalse(view.lastAttendanceList.isEmpty());
    }

    @Test
    public void studentDetected_duringActiveSession_countIncreases() {
        // US-PP-04
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");

        assertEquals(1, view.lastAttendanceCount);
    }

    @Test
    public void twoStudentsDetected_countIsTwo() {
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");
        ble.simulateStudentDetected("Priya Patel");

        assertEquals(2, view.lastAttendanceCount);
    }

    // ── US-PP-05  Student arrives after session stopped (FR-13) ────────────

    @Test
    public void studentDetected_afterSessionStopped_notAddedToList() {
        // US-PP-05 — FR-13 guard
        presenter.startSession("CS101", "5");
        presenter.stopSession();    // session is now inactive
        ble.simulateStudentDetected("Late Student");

        assertEquals(0, repo.getAttendanceCount("cs101"));
    }

    // ── US-PP-06  Duplicate student detection ─────────────────────────────

    @Test
    public void sameStudentDetectedTwice_countRemainsOne() {
        // US-PP-06
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");
        ble.simulateStudentDetected("Yash Sharma");   // second BLE fire

        assertEquals(1, view.lastAttendanceCount);
        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    // ── US-PP-07  Stop session ─────────────────────────────────────────────

    @Test
    public void stopSession_sessionBecomesInactive() {
        // US-PP-07
        presenter.startSession("CS101", "5");
        presenter.stopSession();
        assertFalse(presenter.isSessionActive());
    }

    @Test
    public void stopSession_startButtonReEnabled() {
        presenter.startSession("CS101", "5");
        presenter.stopSession();
        assertTrue(view.startEnabled);
    }

    @Test
    public void stopSession_stopButtonDisabled() {
        presenter.startSession("CS101", "5");
        ble.simulateProfessorBeaconStarted();   // to enable stop button first
        presenter.stopSession();
        assertFalse(view.stopEnabled);
    }

    @Test
    public void stopSession_bleIsStopped() {
        presenter.startSession("CS101", "5");
        presenter.stopSession();
        assertFalse(ble.professorModeStarted);
    }

    // ── US-PP-08  BLE error ────────────────────────────────────────────────

    @Test
    public void bleError_showsErrorToView() {
        // US-PP-08
        presenter.startSession("CS101", "5");
        ble.simulateProfessorError("Bluetooth not enabled.");
        assertFalse(view.errors.isEmpty());
    }

    @Test
    public void bleError_startButtonReEnabled() {
        presenter.startSession("CS101", "5");
        ble.simulateProfessorError("BLE unavailable.");
        assertTrue(view.startEnabled);
    }

    @Test
    public void bleError_sessionMarkedInactive() {
        presenter.startSession("CS101", "5");
        ble.simulateProfessorError("BLE unavailable.");
        assertFalse(presenter.isSessionActive());
    }

    // ── Beacon started callback ────────────────────────────────────────────

    @Test
    public void bleBeaconStarted_notifiesView() {
        presenter.startSession("CS101", "5");
        ble.simulateProfessorBeaconStarted();
        assertTrue(view.beaconStartedFired);
    }

    @Test
    public void bleBeaconStarted_stopButtonEnabled() {
        presenter.startSession("CS101", "5");
        ble.simulateProfessorBeaconStarted();
        assertTrue(view.stopEnabled);
    }

    // ── detach() safety ───────────────────────────────────────────────────

    @Test
    public void detach_noNullPointerOnSubsequentCallbacks() {
        presenter.startSession("CS101", "5");
        presenter.detach();
        // After detach, callbacks from BLE should be silently ignored
        ble.simulateStudentDetected("Ghost Student");
        // No exception = pass
    }

    // ── US-PP-10  Session ID derivation ───────────────────────────────────

    @Test
    public void startSession_sessionIdMatchesStudentInput() {
        // US-PP-10: if professor enters "CS 101", the id is "cs101"
        // A student who types "cs101" will match.
        presenter.startSession("CS 101", "5");
        assertEquals("cs101", presenter.getSessionId());
    }
}
