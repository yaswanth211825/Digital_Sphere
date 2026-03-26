package com.example.digitalsphere;

import com.example.digitalsphere.data.sensor.BarometerReader;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * JVM unit tests for BarometerReader's static utility methods.
 *
 * These methods are pure functions — no Android Context, no SensorManager,
 * no device needed. They run on any JVM in milliseconds.
 *
 * isSameFloor() uses a strict-less-than check:  |diff| < 0.30 hPa
 *   - 0.29 → true  (just under threshold)
 *   - 0.30 → false (AT threshold — not strictly less than)
 *
 * pressureToAltitudeDiff() uses:  |hPaDiff| × 8.3 m/hPa
 */
public class BarometerReaderTest {

    // ── isSameFloor() ─────────────────────────────────────────────────────

    @Test
    public void isSameFloor_identicalReadings_returnsTrue() {
        // Both phones read exactly 1013.25 hPa — diff = 0.0
        // This is the ideal case: same desk, same moment.
        assertTrue(BarometerReader.isSameFloor(1013.25f, 1013.25f));
    }

    @Test
    public void isSameFloor_diffJustUnderThreshold_returnsTrue() {
        // 0.29 hPa apart — within normal sensor noise on the same floor.
        // This is the boundary: one tick below 0.30 should still pass.
        float prof    = 1013.25f;
        float student = 1013.25f + 0.29f;
        assertTrue(BarometerReader.isSameFloor(prof, student));
    }

    @Test
    public void isSameFloor_diffExactlyAtThreshold_returnsFalse() {
        // isSameFloor uses strict-less-than (<), so exactly 0.30 is NOT same floor.
        // We use simple values (100.0 + 100.3) that produce an exact 0.30f diff
        // in IEEE 754, avoiding the rounding trap with 1013.25f + 0.30f.
        assertFalse(BarometerReader.isSameFloor(100.0f, 100.3f));
    }

    @Test
    public void isSameFloor_typicalOneFloorDifference_returnsFalse() {
        // 0.42 hPa ≈ 3.5 metres — typical difference between adjacent floors
        // in a building (one storey = 0.36–0.48 hPa depending on ceiling height).
        float prof    = 1013.25f;
        float student = 1013.25f + 0.42f;
        assertFalse(BarometerReader.isSameFloor(prof, student));
    }

    @Test
    public void isSameFloor_manyFloorsApart_returnsFalse() {
        // 4.0 hPa ≈ 33 metres ≈ 10+ floors — clearly not co-located.
        float prof    = 1013.25f;
        float student = 1013.25f + 4.0f;
        assertFalse(BarometerReader.isSameFloor(prof, student));
    }

    @Test
    public void isSameFloor_studentHigherThanProfessor_handledCorrectly() {
        // Student is on a higher floor → student hPa < professor hPa
        // (pressure decreases with altitude). The method uses Math.abs()
        // so the sign of the difference should not matter.
        float prof    = 1013.25f;
        float student = 1013.25f - 0.42f;  // negative diff
        assertFalse(BarometerReader.isSameFloor(prof, student));
    }

    @Test
    public void isSameFloor_negativeSmallDiff_stillReturnsTrue() {
        // Reverse direction but within threshold — must still pass.
        float prof    = 1013.25f;
        float student = 1013.25f - 0.15f;
        assertTrue(BarometerReader.isSameFloor(prof, student));
    }

    // ── pressureToAltitudeDiff() ──────────────────────────────────────────

    @Test
    public void pressureToAltitudeDiff_oneFloorGap_returnsApprox3Metres() {
        // 0.36 hPa × 8.3 = 2.988 m — roughly one storey.
        float result = BarometerReader.pressureToAltitudeDiff(0.36f);
        assertEquals(2.988f, result, 0.01f);
    }

    @Test
    public void pressureToAltitudeDiff_zeroDiff_returnsZero() {
        // No pressure difference → no altitude difference.
        float result = BarometerReader.pressureToAltitudeDiff(0.0f);
        assertEquals(0.0f, result, 0.001f);
    }

    @Test
    public void pressureToAltitudeDiff_negativeDiff_returnsPositive() {
        // Method uses Math.abs() internally — negative input should
        // still produce a positive altitude difference.
        float result = BarometerReader.pressureToAltitudeDiff(-0.36f);
        assertEquals(2.988f, result, 0.01f);
    }
}
