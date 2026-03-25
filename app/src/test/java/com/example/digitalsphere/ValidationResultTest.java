package com.example.digitalsphere;

import com.example.digitalsphere.domain.model.ValidationResult;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * ─────────────────────────────────────────────────────────
 *  MODULE: ValidationResult
 * ─────────────────────────────────────────────────────────
 *
 * USER STORIES
 * ────────────
 * US-VR-01  As any component receiving a validation result,
 *           when the result is ok(), isValid() is true
 *           and there is no error message.
 *
 * US-VR-02  As any component receiving a validation result,
 *           when the result is error("msg"), isValid() is false
 *           and getMessage() returns exactly "msg".
 *
 * US-VR-03  As a developer, ok() results should never carry a message
 *           so I don't accidentally display noise to the user.
 */
public class ValidationResultTest {

    // ── US-VR-01  ok() ────────────────────────────────────────────────────

    @Test
    public void ok_isValid_returnsTrue() {
        // US-VR-01
        assertTrue(ValidationResult.ok().isValid());
    }

    @Test
    public void ok_getMessage_returnsNull() {
        // US-VR-03: no spurious message on success
        assertNull(ValidationResult.ok().getMessage());
    }

    // ── US-VR-02  error() ─────────────────────────────────────────────────

    @Test
    public void error_isValid_returnsFalse() {
        // US-VR-02
        assertFalse(ValidationResult.error("bad input").isValid());
    }

    @Test
    public void error_getMessage_returnsExactMessage() {
        // US-VR-02
        String msg = "Session name cannot be empty.";
        assertEquals(msg, ValidationResult.error(msg).getMessage());
    }

    @Test
    public void error_withEmptyMessage_isStillInvalid() {
        assertFalse(ValidationResult.error("").isValid());
    }

    // ── Immutability ──────────────────────────────────────────────────────

    @Test
    public void twoOkInstances_areIndependent() {
        ValidationResult r1 = ValidationResult.ok();
        ValidationResult r2 = ValidationResult.ok();
        assertTrue(r1.isValid());
        assertTrue(r2.isValid());
        assertNull(r1.getMessage());
        assertNull(r2.getMessage());
    }

    @Test
    public void twoErrorInstances_haveIndependentMessages() {
        ValidationResult r1 = ValidationResult.error("error A");
        ValidationResult r2 = ValidationResult.error("error B");
        assertEquals("error A", r1.getMessage());
        assertEquals("error B", r2.getMessage());
    }
}
