package com.example.digitalsphere.data.audio.adaptive;

import org.junit.Test;

import static org.junit.Assert.assertTrue;

public class UltrasoundConfidenceEngineTest {

    private final UltrasoundConfidenceEngine engine = new UltrasoundConfidenceEngine();

    @Test
    public void score_increasesForBetterSignals() {
        float weak = engine.score(1.3, 0.34f, false, 0.20f);
        float strong = engine.score(12.0, 1.0f, true, 0.95f);

        assertTrue(strong > weak);
        assertTrue(strong <= 1.0f);
        assertTrue(weak >= 0.0f);
    }

    @Test
    public void normaliseSnr_zeroesSubUnityRatios() {
        assertTrue(UltrasoundConfidenceEngine.normaliseSnr(0.8) == 0.0f);
        assertTrue(UltrasoundConfidenceEngine.normaliseSnr(1.0) == 0.0f);
        assertTrue(UltrasoundConfidenceEngine.normaliseSnr(10.0) > 0.0f);
    }
}
