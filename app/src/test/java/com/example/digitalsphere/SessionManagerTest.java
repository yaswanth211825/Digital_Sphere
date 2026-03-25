package com.example.digitalsphere;

import com.example.digitalsphere.domain.SessionManager;
import com.example.digitalsphere.domain.model.ValidationResult;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ─────────────────────────────────────────────────────────
 *  MODULE: SessionManager
 *  Pure-Java unit tests — no Android runtime needed.
 * ─────────────────────────────────────────────────────────
 *
 * USER STORIES
 * ────────────
 * US-SM-01  As a professor, when I type "CS 101" as session name,
 *           the system generates "cs101" as the session ID,
 *           so students can type it without worrying about spacing or case.
 *
 * US-SM-02  As a professor, when I type "  Math  " (extra spaces),
 *           the system strips all whitespace and lowercases to "math".
 *
 * US-SM-03  As a professor, when I leave the session name blank,
 *           I get a clear error so I know what to fix.
 *
 * US-SM-04  As a professor, when I type a single character ("A"),
 *           I get an error saying the name is too short.
 *
 * US-SM-05  As a professor, when I type "10" for duration,
 *           the system parses it as 10 minutes.
 *
 * US-SM-06  As a professor, when I type "abc" for duration,
 *           I get a user-friendly error (not a stack trace).
 *
 * US-SM-07  As a professor, when I type "0" for duration,
 *           I get a range error explaining the valid range.
 *
 * US-SM-08  As a professor, when I leave the duration field blank,
 *           the default duration (5 min) is used automatically.
 *
 * US-SM-09  As a student, when I leave my name blank,
 *           I get a clear error before scanning starts.
 *
 * US-SM-10  As a student, when I leave the session field blank,
 *           I get a clear error before scanning starts.
 *
 * US-SM-11  As a system, a pseudo device-ID is built deterministically
 *           from the student name + session ID so duplicate detection works.
 */
public class SessionManagerTest {

    private SessionManager sm;

    @Before
    public void setUp() {
        sm = new SessionManager();
    }

    // ── US-SM-01  Session ID creation ──────────────────────────────────────

    @Test
    public void createSessionId_stripsSpacesAndLowercases() {
        // US-SM-01
        assertEquals("cs101", sm.createSessionId("CS 101"));
    }

    @Test
    public void createSessionId_trimsLeadingAndTrailingSpaces() {
        // US-SM-02
        assertEquals("math", sm.createSessionId("  Math  "));
    }

    @Test
    public void createSessionId_multipleInternalSpaces() {
        assertEquals("advancedphysics", sm.createSessionId("Advanced Physics"));
    }

    @Test
    public void createSessionId_nullInputReturnsEmpty() {
        assertEquals("", sm.createSessionId(null));
    }

    @Test
    public void createSessionId_alreadyLowercaseNoChange() {
        assertEquals("cs101", sm.createSessionId("cs101"));
    }

    // ── US-SM-03  Blank session name ───────────────────────────────────────

    @Test
    public void validateSessionName_emptyString_returnsError() {
        // US-SM-03
        ValidationResult r = sm.validateSessionName("");
        assertFalse(r.isValid());
        assertNotNull(r.getMessage());
        assertFalse(r.getMessage().isEmpty());
    }

    @Test
    public void validateSessionName_nullInput_returnsError() {
        ValidationResult r = sm.validateSessionName(null);
        assertFalse(r.isValid());
    }

    @Test
    public void validateSessionName_whitespaceOnly_returnsError() {
        ValidationResult r = sm.validateSessionName("   ");
        assertFalse(r.isValid());
    }

    // ── US-SM-04  Too-short session name ───────────────────────────────────

    @Test
    public void validateSessionName_singleChar_returnsError() {
        // US-SM-04
        ValidationResult r = sm.validateSessionName("A");
        assertFalse(r.isValid());
        assertNotNull(r.getMessage());
    }

    @Test
    public void validateSessionName_twoChars_isValid() {
        // boundary: exactly 2 chars should pass
        ValidationResult r = sm.validateSessionName("CS");
        assertTrue(r.isValid());
    }

    @Test
    public void validateSessionName_normalName_isValid() {
        ValidationResult r = sm.validateSessionName("CS101");
        assertTrue(r.isValid());
        assertNull(r.getMessage());
    }

    // ── US-SM-05  Valid duration ───────────────────────────────────────────

    @Test
    public void parseDuration_validInteger_returnsParsedValue() {
        // US-SM-05
        assertEquals(10, sm.parseDuration("10"));
    }

    @Test
    public void parseDuration_minBoundary_returnsOne() {
        assertEquals(1, sm.parseDuration("1"));
    }

    @Test
    public void parseDuration_maxBoundary_returns180() {
        assertEquals(180, sm.parseDuration("180"));
    }

    // ── US-SM-06  Non-numeric duration ────────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void parseDuration_nonNumeric_throwsIllegalArgument() {
        // US-SM-06
        sm.parseDuration("abc");
    }

    @Test
    public void parseDuration_nonNumeric_exceptionMessageIsUserFriendly() {
        // US-SM-06: message should not be a raw exception name
        try {
            sm.parseDuration("xyz");
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertNotNull(e.getMessage());
            assertTrue("Expected a human-readable message",
                    e.getMessage().length() > 10);
        }
    }

    // ── US-SM-07  Out-of-range duration ───────────────────────────────────

    @Test(expected = IllegalArgumentException.class)
    public void parseDuration_zero_throwsIllegalArgument() {
        // US-SM-07
        sm.parseDuration("0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseDuration_negative_throwsIllegalArgument() {
        sm.parseDuration("-5");
    }

    @Test(expected = IllegalArgumentException.class)
    public void parseDuration_tooLarge_throwsIllegalArgument() {
        sm.parseDuration("181");
    }

    // ── US-SM-08  Blank duration → default ────────────────────────────────

    @Test
    public void parseDuration_emptyString_returnsDefault() {
        // US-SM-08
        assertEquals(sm.getDefaultDuration(), sm.parseDuration(""));
    }

    @Test
    public void parseDuration_nullInput_returnsDefault() {
        assertEquals(sm.getDefaultDuration(), sm.parseDuration(null));
    }

    @Test
    public void parseDuration_whitespaceOnly_returnsDefault() {
        assertEquals(sm.getDefaultDuration(), sm.parseDuration("   "));
    }

    // ── US-SM-09  Blank student name ──────────────────────────────────────

    @Test
    public void validateStudentName_emptyString_returnsError() {
        // US-SM-09
        ValidationResult r = sm.validateStudentName("");
        assertFalse(r.isValid());
        assertNotNull(r.getMessage());
    }

    @Test
    public void validateStudentName_null_returnsError() {
        assertFalse(sm.validateStudentName(null).isValid());
    }

    @Test
    public void validateStudentName_validName_isValid() {
        assertTrue(sm.validateStudentName("Yash Sharma").isValid());
    }

    // ── US-SM-10  Blank session input (student side) ───────────────────────

    @Test
    public void validateSessionInput_emptyString_returnsError() {
        // US-SM-10
        ValidationResult r = sm.validateSessionInput("");
        assertFalse(r.isValid());
        assertNotNull(r.getMessage());
    }

    @Test
    public void validateSessionInput_null_returnsError() {
        assertFalse(sm.validateSessionInput(null).isValid());
    }

    @Test
    public void validateSessionInput_validInput_isValid() {
        assertTrue(sm.validateSessionInput("CS101").isValid());
    }

    // ── US-SM-11  Pseudo device-ID is deterministic ────────────────────────

    @Test
    public void buildPseudoDeviceId_formatIsCorrect() {
        // US-SM-11: "Yash Sharma" + "cs101" → "beacon_yash_sharma_cs101"
        String id = sm.buildPseudoDeviceId("Yash Sharma", "cs101");
        assertEquals("beacon_yash_sharma_cs101", id);
    }

    @Test
    public void buildPseudoDeviceId_isDeterministic() {
        // Same input always produces same output (idempotent duplicate check)
        String id1 = sm.buildPseudoDeviceId("Priya", "math");
        String id2 = sm.buildPseudoDeviceId("Priya", "math");
        assertEquals(id1, id2);
    }

    @Test
    public void buildPseudoDeviceId_differentNames_produceDifferentIds() {
        String id1 = sm.buildPseudoDeviceId("Yash",  "cs101");
        String id2 = sm.buildPseudoDeviceId("Priya", "cs101");
        assertNotEquals(id1, id2);
    }

    @Test
    public void buildPseudoDeviceId_differentSessions_produceDifferentIds() {
        String id1 = sm.buildPseudoDeviceId("Yash", "cs101");
        String id2 = sm.buildPseudoDeviceId("Yash", "math");
        assertNotEquals(id1, id2);
    }
}
