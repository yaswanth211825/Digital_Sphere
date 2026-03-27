package com.example.digitalsphere;

import com.example.digitalsphere.domain.verification.SignalReading;
import com.example.digitalsphere.domain.verification.VerificationEngine;
import com.example.digitalsphere.domain.verification.VerificationResult;
import com.example.digitalsphere.domain.verification.VerificationStatus;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VerificationEngineTest {

    private static final float PROFESSOR_PRESSURE = 1013.00f;
    private static final int SESSION_TOKEN = 2;
    private static final float[] STRONG_AUDIO_HASH =
            {0.10f, 0.85f, 0.30f, 1.00f, 0.45f, 0.78f, 0.20f, 0.60f};
    private static final float[] SUSPICIOUS_AUDIO_HASH =
            {0.55f, 0.60f, 0.52f, 0.58f, 0.54f, 0.59f, 0.53f, 0.57f};

    @Test
    public void happyPath() {
        VerificationEngine engine = new VerificationEngine(PROFESSOR_PRESSURE, SESSION_TOKEN, STRONG_AUDIO_HASH);

        VerificationResult result = engine.verify(new SignalReading.Builder()
                .rssiAverage(-58)
                .bleRssiSampleCount(12)
                .barometerAvailable(true)
                .studentPressureHPa(1013.08f)
                .professorPressureHPa(PROFESSOR_PRESSURE)
                .pressureVarianceHPa(0.01f)
                .detectedUltrasoundToken(SESSION_TOKEN)
                .expectedUltrasoundToken(SESSION_TOKEN)
                .ultrasoundConfidence(0.92f)
                .studentAmbientHash(STRONG_AUDIO_HASH)
                .professorAmbientHash(STRONG_AUDIO_HASH)
                .audioSnrEstimate(18.0f)
                .build());

        assertEquals(VerificationStatus.PRESENT, result.getStatus());
        assertTrue(result.isPresent());
    }

    @Test
    public void wrongFloor() {
        VerificationEngine engine = new VerificationEngine(PROFESSOR_PRESSURE, SESSION_TOKEN, STRONG_AUDIO_HASH);

        VerificationResult result = engine.verify(new SignalReading.Builder()
                .rssiAverage(-58)
                .bleRssiSampleCount(12)
                .barometerAvailable(true)
                .studentPressureHPa(1013.45f)
                .professorPressureHPa(PROFESSOR_PRESSURE)
                .pressureVarianceHPa(0.01f)
                .detectedUltrasoundToken(SESSION_TOKEN)
                .expectedUltrasoundToken(SESSION_TOKEN)
                .ultrasoundConfidence(0.92f)
                .studentAmbientHash(STRONG_AUDIO_HASH)
                .professorAmbientHash(STRONG_AUDIO_HASH)
                .audioSnrEstimate(18.0f)
                .build());

        assertEquals(VerificationStatus.REJECTED_FLOOR, result.getStatus());
        assertFalse(result.isPresent());
    }

    @Test
    public void noBarometer() {
        VerificationEngine engine = new VerificationEngine(Float.NaN, SESSION_TOKEN, null);

        VerificationResult result = engine.verify(new SignalReading.Builder()
                .rssiAverage(-60)
                .bleRssiSampleCount(10)
                .barometerAvailable(false)
                .detectedUltrasoundToken(SESSION_TOKEN)
                .expectedUltrasoundToken(SESSION_TOKEN)
                .ultrasoundConfidence(0.88f)
                .build());

        assertEquals(VerificationStatus.PRESENT, result.getStatus());
        assertTrue(result.isPresent());
    }

    @Test
    public void wrongRoom() {
        VerificationEngine engine = new VerificationEngine(PROFESSOR_PRESSURE, SESSION_TOKEN, null);

        VerificationResult result = engine.verify(new SignalReading.Builder()
                .rssiAverage(-60)
                .bleRssiSampleCount(10)
                .barometerAvailable(true)
                .studentPressureHPa(1013.05f)
                .professorPressureHPa(PROFESSOR_PRESSURE)
                .pressureVarianceHPa(0.01f)
                .detectedUltrasoundToken(7)
                .expectedUltrasoundToken(SESSION_TOKEN)
                .ultrasoundConfidence(0.82f)
                .build());

        assertEquals(VerificationStatus.REJECTED_ROOM, result.getStatus());
        assertFalse(result.isPresent());
    }

    @Test
    public void audioSuspicious() {
        VerificationEngine engine = new VerificationEngine(PROFESSOR_PRESSURE, SESSION_TOKEN, STRONG_AUDIO_HASH);

        VerificationResult result = engine.verify(new SignalReading.Builder()
                .rssiAverage(-65)
                .bleRssiSampleCount(8)
                .barometerAvailable(true)
                .studentPressureHPa(1013.10f)
                .professorPressureHPa(PROFESSOR_PRESSURE)
                .pressureVarianceHPa(0.01f)
                .detectedUltrasoundToken(SESSION_TOKEN)
                .expectedUltrasoundToken(SESSION_TOKEN)
                .ultrasoundConfidence(0.55f)
                .studentAmbientHash(SUSPICIOUS_AUDIO_HASH)
                .professorAmbientHash(STRONG_AUDIO_HASH)
                .audioSnrEstimate(2.0f)
                .build());

        assertEquals(VerificationStatus.FLAGGED, result.getStatus());
        assertTrue(result.getFusionScore() >= 0.55f);
        assertTrue(result.getFusionScore() < 0.75f);
    }

    @Test
    public void flaggedStillMarksPresent() {
        VerificationEngine engine = new VerificationEngine(PROFESSOR_PRESSURE, SESSION_TOKEN, STRONG_AUDIO_HASH);

        VerificationResult result = engine.verify(new SignalReading.Builder()
                .rssiAverage(-65)
                .bleRssiSampleCount(8)
                .barometerAvailable(true)
                .studentPressureHPa(1013.10f)
                .professorPressureHPa(PROFESSOR_PRESSURE)
                .pressureVarianceHPa(0.01f)
                .detectedUltrasoundToken(SESSION_TOKEN)
                .expectedUltrasoundToken(SESSION_TOKEN)
                .ultrasoundConfidence(0.55f)
                .studentAmbientHash(SUSPICIOUS_AUDIO_HASH)
                .professorAmbientHash(STRONG_AUDIO_HASH)
                .audioSnrEstimate(2.0f)
                .build());

        assertEquals(VerificationStatus.FLAGGED, result.getStatus());
        assertTrue(result.isPresent());
    }

    @Test
    public void rejectedNotPresent() {
        VerificationEngine engine = new VerificationEngine(PROFESSOR_PRESSURE, SESSION_TOKEN, null);

        VerificationResult result = engine.verify(new SignalReading.Builder()
                .rssiAverage(-58)
                .bleRssiSampleCount(12)
                .barometerAvailable(true)
                .studentPressureHPa(1013.45f)
                .professorPressureHPa(PROFESSOR_PRESSURE)
                .pressureVarianceHPa(0.01f)
                .detectedUltrasoundToken(SESSION_TOKEN)
                .expectedUltrasoundToken(SESSION_TOKEN)
                .ultrasoundConfidence(0.92f)
                .build());

        assertEquals(VerificationStatus.REJECTED_FLOOR, result.getStatus());
        assertFalse(result.isPresent());
    }
}
