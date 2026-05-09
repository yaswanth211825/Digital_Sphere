package com.example.digitalsphere.data.audio.adaptive;

/**
 * Converts low-level decode quality metrics into a single [0,1] confidence.
 */
public final class UltrasoundConfidenceEngine {

    private static final float WEIGHT_SNR = 0.35f;
    private static final float WEIGHT_REPEAT = 0.30f;
    private static final float WEIGHT_SYMBOL = 0.20f;
    private static final float WEIGHT_CRC = 0.15f;

    public float score(double snrLinear,
                       float repeatAgreement,
                       boolean crcValid,
                       float symbolReliability) {
        float snrScore = normaliseSnr(snrLinear);
        float crcScore = crcValid ? 1.0f : 0.0f;
        float clampedRepeat = clamp(repeatAgreement);
        float clampedSymbol = clamp(symbolReliability);

        return clamp((snrScore * WEIGHT_SNR)
                + (clampedRepeat * WEIGHT_REPEAT)
                + (clampedSymbol * WEIGHT_SYMBOL)
                + (crcScore * WEIGHT_CRC));
    }

    static float normaliseSnr(double snrLinear) {
        if (snrLinear <= 1.0) return 0.0f;
        double db = 10.0 * Math.log10(snrLinear);
        return clamp((float) (db / 20.0));
    }

    static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
