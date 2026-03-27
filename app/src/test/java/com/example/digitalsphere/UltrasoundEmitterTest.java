package com.example.digitalsphere;

import com.example.digitalsphere.data.audio.UltrasoundEmitter;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JVM unit tests for UltrasoundEmitter's public API.
 *
 * Tests cover:
 * - Session token derivation (via getSessionToken())
 * - Initial state
 * - Token range validation across diverse inputs
 *
 * AudioTrack-dependent methods (startEmitting/stopEmitting) require
 * Android hardware and are tested in androidTest/.
 */
public class UltrasoundEmitterTest {

    // ══════════════════════════════════════════════════════════════════
    //  getSessionToken() — token derivation via public API
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void token_normalSession_isInRange0to15() {
        UltrasoundEmitter emitter = new UltrasoundEmitter("cs101");
        int token = emitter.getSessionToken();
        assertTrue("Token must be >= 0, was: " + token, token >= 0);
        assertTrue("Token must be <= 15, was: " + token, token <= 15);
    }

    @Test
    public void token_sameSession_alwaysProducesSameToken() {
        int expected = new UltrasoundEmitter("physics301").getSessionToken();
        for (int i = 0; i < 100; i++) {
            assertEquals("Token must be deterministic",
                    expected, new UltrasoundEmitter("physics301").getSessionToken());
        }
    }

    @Test
    public void token_differentSessions_areDeterministic() {
        int t1 = new UltrasoundEmitter("cs101").getSessionToken();
        int t2 = new UltrasoundEmitter("math202").getSessionToken();
        // Re-derive to confirm determinism
        assertEquals(t1, new UltrasoundEmitter("cs101").getSessionToken());
        assertEquals(t2, new UltrasoundEmitter("math202").getSessionToken());
    }

    @Test
    public void token_nullSession_returnsZero() {
        UltrasoundEmitter emitter = new UltrasoundEmitter(null);
        assertEquals("Null session should produce token 0", 0, emitter.getSessionToken());
    }

    @Test
    public void token_emptySession_returnsZero() {
        UltrasoundEmitter emitter = new UltrasoundEmitter("");
        assertEquals("Empty session should produce token 0", 0, emitter.getSessionToken());
    }

    @Test
    public void token_wideVarietyOfSessions_allInRange() {
        String[] sessions = {
                "a", "ab", "abc", "CS101", "MATH202", "PHY303",
                "session_with_underscores", "CamelCaseSession",
                "12345", "special!@#$%", "   spaces   ",
                "verylongsessionnamethatexceedstwentybytes",
                "unicode日本語", "MiXeD cAsE 123!"
        };
        for (String s : sessions) {
            int token = new UltrasoundEmitter(s).getSessionToken();
            assertTrue("Token for '" + s + "' must be >= 0, was: " + token, token >= 0);
            assertTrue("Token for '" + s + "' must be <= 15, was: " + token, token <= 15);
        }
    }

    @Test
    public void token_coversMultipleValues() {
        // Verify that across many sessions we get more than one distinct token
        // (statistical: with 16 possible values and 100 inputs, collision for ALL
        // landing on one value is astronomically unlikely)
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int i = 0; i < 100; i++) {
            seen.add(new UltrasoundEmitter("session" + i).getSessionToken());
        }
        assertTrue("Should produce at least 4 distinct tokens across 100 sessions, got: "
                + seen.size(), seen.size() >= 4);
    }

    @Test
    public void token_integerMinValueHashSafe() {
        // "polygenelubricants" has hashCode() == Integer.MIN_VALUE in Java.
        // Math.abs(Integer.MIN_VALUE) returns Integer.MIN_VALUE (negative!).
        // Our implementation uses (hash & 0x7FFFFFFF) to avoid this.
        // Verify no crash and non-negative result.
        UltrasoundEmitter emitter = new UltrasoundEmitter("polygenelubricants");
        int token = emitter.getSessionToken();
        assertTrue("Token must be non-negative even for MIN_VALUE hash", token >= 0);
        assertTrue("Token must be <= 15", token <= 15);
    }

    // ══════════════════════════════════════════════════════════════════
    //  isEmitting() — initial state
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void isEmitting_initiallyFalse() {
        UltrasoundEmitter emitter = new UltrasoundEmitter("test");
        assertFalse("Should not be emitting before startEmitting()", emitter.isEmitting());
    }

    @Test
    public void stopEmitting_beforeStart_doesNotCrash() {
        UltrasoundEmitter emitter = new UltrasoundEmitter("test");
        // Should be a safe no-op
        emitter.stopEmitting();
        assertFalse(emitter.isEmitting());
    }

    @Test
    public void stopEmitting_calledMultipleTimes_doesNotCrash() {
        UltrasoundEmitter emitter = new UltrasoundEmitter("test");
        emitter.stopEmitting();
        emitter.stopEmitting();
        emitter.stopEmitting();
        assertFalse(emitter.isEmitting());
    }

    // ══════════════════════════════════════════════════════════════════
    //  Constructor variants
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void constructor_withoutCallback_works() {
        UltrasoundEmitter emitter = new UltrasoundEmitter("cs101");
        assertNotNull(emitter);
        assertTrue(emitter.getSessionToken() >= 0);
    }

    @Test
    public void constructor_withCallback_works() {
        UltrasoundEmitter emitter = new UltrasoundEmitter("cs101",
                new UltrasoundEmitter.EmitterCallback() {
                    @Override public void onEmissionStarted() {}
                    @Override public void onEmissionFailed(String reason) {}
                });
        assertNotNull(emitter);
        assertTrue(emitter.getSessionToken() >= 0);
    }

    @Test
    public void constructor_withNullCallback_works() {
        UltrasoundEmitter emitter = new UltrasoundEmitter("cs101", null);
        assertNotNull(emitter);
    }

    // ══════════════════════════════════════════════════════════════════
    //  Token consistency between emitter and detector
    // ══════════════════════════════════════════════════════════════════

    @Test
    public void token_matchesBetweenEmitterInstances() {
        // Two emitters with same session should produce identical tokens
        // (critical for professor↔student matching)
        String session = "cs101_spring2026";
        UltrasoundEmitter e1 = new UltrasoundEmitter(session);
        UltrasoundEmitter e2 = new UltrasoundEmitter(session);
        assertEquals("Two emitters with same session must have same token",
                e1.getSessionToken(), e2.getSessionToken());
    }
}
