package com.example.digitalsphere.data.audio.adaptive;

/**
 * Structured telemetry payload for adaptive-ultrasound experiments.
 */
public final class UltrasoundTelemetry {

    private final String deviceModel;
    private final int f0;
    private final int f1;
    private final double snr;
    private final double noiseFloor;
    private final boolean success;
    private final long latencyMs;
    private final float confidence;

    public UltrasoundTelemetry(String deviceModel,
                               int f0,
                               int f1,
                               double snr,
                               double noiseFloor,
                               boolean success,
                               long latencyMs,
                               float confidence) {
        this.deviceModel = deviceModel;
        this.f0 = f0;
        this.f1 = f1;
        this.snr = snr;
        this.noiseFloor = noiseFloor;
        this.success = success;
        this.latencyMs = latencyMs;
        this.confidence = confidence;
    }

    public String getDeviceModel() {
        return deviceModel;
    }

    public int getF0() {
        return f0;
    }

    public int getF1() {
        return f1;
    }

    public double getSnr() {
        return snr;
    }

    public double getNoiseFloor() {
        return noiseFloor;
    }

    public boolean isSuccess() {
        return success;
    }

    public long getLatencyMs() {
        return latencyMs;
    }

    public float getConfidence() {
        return confidence;
    }
}
