package com.example.digitalsphere.data.audio.adaptive;

/**
 * Pure-Java frequency energy utility for sweep analysis and FSK detection.
 */
public final class FrequencyEnergyAnalyzer {

    private FrequencyEnergyAnalyzer() {}

    public static double goertzel(short[] samples,
                                  int offset,
                                  int numSamples,
                                  int sampleRate,
                                  int targetHz) {
        if (samples == null || numSamples <= 0 || offset < 0 || offset + numSamples > samples.length) {
            return 0.0;
        }

        double k = (double) targetHz * numSamples / sampleRate;
        double w = 2.0 * Math.PI * k / numSamples;
        double coeff = 2.0 * Math.cos(w);

        double s0 = 0.0;
        double s1 = 0.0;
        double s2 = 0.0;

        for (int i = offset; i < offset + numSamples; i++) {
            s0 = samples[i] + coeff * s1 - s2;
            s2 = s1;
            s1 = s0;
        }

        return Math.sqrt(Math.max(0.0, s1 * s1 + s2 * s2 - coeff * s1 * s2));
    }

    public static double rms(short[] samples, int offset, int numSamples) {
        if (samples == null || numSamples <= 0 || offset < 0 || offset + numSamples > samples.length) {
            return 0.0;
        }

        double sumSquares = 0.0;
        for (int i = offset; i < offset + numSamples; i++) {
            double sample = samples[i];
            sumSquares += sample * sample;
        }
        return Math.sqrt(sumSquares / numSamples);
    }
}
