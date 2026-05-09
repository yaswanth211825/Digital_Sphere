package com.example.digitalsphere.data.audio.adaptive;

import java.util.List;

/**
 * Negotiated session parameters for the adaptive ultrasound path.
 */
public final class UltrasoundSessionConfig {

    public static final int DEFAULT_SAMPLE_RATE = 48000;
    public static final int DEFAULT_SYMBOL_DURATION_MS = 40;
    public static final int DEFAULT_REPEAT_COUNT = 3;
    public static final String DEFAULT_PREAMBLE = "10101010";
    public static final float DEFAULT_THRESHOLD_MULTIPLIER = 2.0f;

    private final int f0;
    private final int f1;
    private final int sampleRate;
    private final int symbolDurationMs;
    private final int repeatCount;
    private final String preamble;
    private final float thresholdMultiplier;

    private UltrasoundSessionConfig(Builder builder) {
        this.f0 = builder.f0;
        this.f1 = builder.f1;
        this.sampleRate = builder.sampleRate;
        this.symbolDurationMs = builder.symbolDurationMs;
        this.repeatCount = builder.repeatCount;
        this.preamble = builder.preamble;
        this.thresholdMultiplier = builder.thresholdMultiplier;
    }

    public int getF0() {
        return f0;
    }

    public int getF1() {
        return f1;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getSymbolDurationMs() {
        return symbolDurationMs;
    }

    public int getRepeatCount() {
        return repeatCount;
    }

    public String getPreamble() {
        return preamble;
    }

    public float getThresholdMultiplier() {
        return thresholdMultiplier;
    }

    public int getSamplesPerSymbol() {
        return (sampleRate * symbolDurationMs) / 1000;
    }

    public int getPreambleBitCount() {
        return preamble != null ? preamble.length() : 0;
    }

    public byte[] toCompactBleBytes() {
        List<Integer> sweepFrequencies = UltrasoundProfiler.buildSweepFrequencies();
        int f0Index = sweepFrequencies.indexOf(f0);
        int f1Index = sweepFrequencies.indexOf(f1);
        if (f0Index < 0 || f1Index < 0) {
            return null;
        }
        return new byte[] {
                (byte) f0Index,
                (byte) f1Index,
                (byte) Math.max(1, Math.min(255, symbolDurationMs)),
                (byte) Math.max(1, Math.min(255, repeatCount))
        };
    }

    public static UltrasoundSessionConfig fromCompactBleBytes(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            return null;
        }

        List<Integer> sweepFrequencies = UltrasoundProfiler.buildSweepFrequencies();
        int f0Index = bytes[0] & 0xFF;
        int f1Index = bytes[1] & 0xFF;
        if (f0Index >= sweepFrequencies.size() || f1Index >= sweepFrequencies.size() || f0Index == f1Index) {
            return null;
        }

        int symbolDurationMs = bytes[2] & 0xFF;
        int repeatCount = bytes[3] & 0xFF;
        if (symbolDurationMs <= 0 || repeatCount <= 0) {
            return null;
        }

        return UltrasoundSessionConfig.builder(
                        sweepFrequencies.get(f0Index),
                        sweepFrequencies.get(f1Index))
                .symbolDurationMs(symbolDurationMs)
                .repeatCount(repeatCount)
                .build();
    }

    public static Builder builder(int f0, int f1) {
        return new Builder(f0, f1);
    }

    public static final class Builder {
        private final int f0;
        private final int f1;
        private int sampleRate = DEFAULT_SAMPLE_RATE;
        private int symbolDurationMs = DEFAULT_SYMBOL_DURATION_MS;
        private int repeatCount = DEFAULT_REPEAT_COUNT;
        private String preamble = DEFAULT_PREAMBLE;
        private float thresholdMultiplier = DEFAULT_THRESHOLD_MULTIPLIER;

        private Builder(int f0, int f1) {
            this.f0 = f0;
            this.f1 = f1;
        }

        public Builder sampleRate(int sampleRate) {
            this.sampleRate = sampleRate;
            return this;
        }

        public Builder symbolDurationMs(int symbolDurationMs) {
            this.symbolDurationMs = symbolDurationMs;
            return this;
        }

        public Builder repeatCount(int repeatCount) {
            this.repeatCount = repeatCount;
            return this;
        }

        public Builder preamble(String preamble) {
            this.preamble = preamble;
            return this;
        }

        public Builder thresholdMultiplier(float thresholdMultiplier) {
            this.thresholdMultiplier = thresholdMultiplier;
            return this;
        }

        public UltrasoundSessionConfig build() {
            if (f0 <= 0 || f1 <= 0 || f0 == f1) {
                throw new IllegalArgumentException("FSK carrier frequencies must be positive and distinct.");
            }
            if (sampleRate <= 0) {
                throw new IllegalArgumentException("sampleRate must be positive.");
            }
            if (symbolDurationMs <= 0) {
                throw new IllegalArgumentException("symbolDurationMs must be positive.");
            }
            if (repeatCount <= 0) {
                throw new IllegalArgumentException("repeatCount must be positive.");
            }
            if (preamble == null || preamble.isEmpty()) {
                throw new IllegalArgumentException("preamble must not be empty.");
            }
            if (thresholdMultiplier <= 0f) {
                throw new IllegalArgumentException("thresholdMultiplier must be positive.");
            }
            return new UltrasoundSessionConfig(this);
        }
    }
}
