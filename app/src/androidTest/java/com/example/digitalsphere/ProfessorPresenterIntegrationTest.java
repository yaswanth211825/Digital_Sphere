package com.example.digitalsphere;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.example.digitalsphere.data.db.AttendanceRepository;
import com.example.digitalsphere.data.export.CsvExporter;
import com.example.digitalsphere.helper.FakeBleManager;
import com.example.digitalsphere.helper.FakeProfessorView;
import com.example.digitalsphere.presenter.ProfessorPresenter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/**
 * ─────────────────────────────────────────────────────────────────────────
 *  INTEGRATION TEST — ProfessorPresenter + real AttendanceRepository
 *
 *  BLE is still faked (hardware not available in CI), but SQLite is real.
 *  This proves the full path:
 *     BLE callback → Presenter logic → real SQLite → View update
 * ─────────────────────────────────────────────────────────────────────────
 *
 * USER STORIES
 * ────────────
 * US-IPP-01  As a professor, when a student name arrives via BLE callback,
 *            the real SQLite DB has a record and the view shows the name.
 *
 * US-IPP-02  As a professor, detecting the same student twice via BLE
 *            writes exactly one record to real SQLite — no duplicates
 *            even when the hardware fires two events.
 *
 * US-IPP-03  As a professor, students detected during an active session
 *            are visible in the attendance list immediately after detection.
 *
 * US-IPP-04  As a professor, stopping the session persists all records in
 *            an internal CSV archive so attendance is preserved after class.
 *
 * US-IPP-05  As a professor, restarting the same session name starts with
 *            a clean live attendance list instead of reusing old names.
 *
 * US-IPP-06  As a professor, the attendance count shown in the view
 *            matches the real number of records in the SQLite table.
 */
@RunWith(AndroidJUnit4.class)
public class ProfessorPresenterIntegrationTest {

    private ProfessorPresenter  presenter;
    private FakeProfessorView   view;
    private FakeBleManager      ble;
    private AttendanceRepository repo;
    private Context              ctx;

    @Before
    public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        ctx.deleteDatabase("attendance.db");
        deleteInternalArchives();

        view = new FakeProfessorView();
        ble  = new FakeBleManager();
        repo = new AttendanceRepository(ctx);

        presenter = new ProfessorPresenter(ctx, repo, ble) {
            @Override protected void startTimer(int minutes) { /* no-op: avoid CountDownTimer in tests */ }

            @Override
            protected void archiveSessionSnapshot(List<String> records, String sessionId) {
                try {
                    CsvExporter.archiveToInternalStorage(ctx, records, sessionId);
                } catch (IOException e) {
                    fail("archiveSessionSnapshot failed: " + e.getMessage());
                }
            }
        };
        presenter.attach(view);
    }

    @After
    public void tearDown() {
        presenter.detach();
        ctx.deleteDatabase("attendance.db");
        deleteInternalArchives();
    }

    // ── US-IPP-01  BLE callback → real DB write → view update ────────────

    @Test
    public void studentDetected_realDbHasRecord() {
        // US-IPP-01
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");

        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    @Test
    public void studentDetected_viewShowsStudentName() {
        // US-IPP-01
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");

        assertFalse(view.lastAttendanceList.isEmpty());
        assertTrue(view.lastAttendanceList.get(0).contains("Yash Sharma"));
    }

    // ── US-IPP-02  Duplicate BLE event → single DB record ─────────────────

    @Test
    public void studentDetectedTwice_dbHasOneRecord() {
        // US-IPP-02: BLE hardware sometimes fires twice for the same device
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");
        ble.simulateStudentDetected("Yash Sharma");

        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    @Test
    public void studentDetectedTwice_viewCountIsOne() {
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");
        ble.simulateStudentDetected("Yash Sharma");

        assertEquals(1, view.lastAttendanceCount);
    }

    // ── US-IPP-03  Multiple students during active session ─────────────────

    @Test
    public void threeStudentsDetected_dbHasThreeRecords() {
        // US-IPP-03
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");
        ble.simulateStudentDetected("Priya Patel");
        ble.simulateStudentDetected("Rahul Gupta");

        assertEquals(3, repo.getAttendanceCount("cs101"));
    }

    @Test
    public void threeStudentsDetected_viewCountIsThree() {
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");
        ble.simulateStudentDetected("Priya Patel");
        ble.simulateStudentDetected("Rahul Gupta");

        assertEquals(3, view.lastAttendanceCount);
    }

    // ── US-IPP-04  Stop session — records persist ──────────────────────────

    @Test
    public void stopSession_recordsStillInDb() {
        // US-IPP-04
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");
        presenter.stopSession();

        // Re-query from DB directly — data must survive stop
        assertEquals(1, repo.getAttendanceCount("cs101"));
    }

    @Test
    public void stopSession_createsInternalArchive() throws Exception {
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");
        presenter.stopSession();

        File[] files = CsvExporter.getInternalArchiveDirectory(ctx)
                .listFiles((dir, name) -> name.startsWith("attendance_cs101_"));

        assertNotNull(files);
        assertEquals(1, files.length);
        String contents = new String(Files.readAllBytes(files[0].toPath()), StandardCharsets.UTF_8);
        assertTrue(contents.contains("Yash Sharma"));
    }

    @Test
    public void stopSession_viewRefreshesWithPersistedData() {
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");
        presenter.stopSession();

        // The view should have been refreshed on stop
        assertEquals(1, view.lastAttendanceCount);
    }

    // ── US-IPP-05  Session restart — same session ID accumulates ──────────

    @Test
    public void sessionRestarted_startsWithFreshAttendance() {
        // US-IPP-05
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash Sharma");
        presenter.stopSession();

        // Restart same session — Priya joins now
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Priya Patel");

        assertEquals(1, repo.getAttendanceCount("cs101"));
        assertTrue(repo.getAttendance("cs101").get(0).contains("Priya Patel"));
    }

    // ── US-IPP-06  View count matches DB count ────────────────────────────

    @Test
    public void viewCount_alwaysMatchesDbCount() {
        // US-IPP-06
        presenter.startSession("CS101", "5");
        ble.simulateStudentDetected("Yash");
        ble.simulateStudentDetected("Priya");

        int dbCount   = repo.getAttendanceCount("cs101");
        int viewCount = view.lastAttendanceCount;
        assertEquals(dbCount, viewCount);
    }

    // ── FR-13 guard with real DB ───────────────────────────────────────────

    @Test
    public void studentDetectedAfterStop_realDbIsNotModified() {
        presenter.startSession("CS101", "5");
        presenter.stopSession();

        ble.simulateStudentDetected("Late Student");

        assertEquals(0, repo.getAttendanceCount("cs101"));
    }

    private void deleteInternalArchives() {
        File dir = CsvExporter.getInternalArchiveDirectory(ctx);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                file.delete();
            }
        }
        if (dir.exists()) {
            dir.delete();
        }
    }
}
