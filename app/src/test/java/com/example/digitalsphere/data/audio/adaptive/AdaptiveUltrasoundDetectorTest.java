package com.example.digitalsphere.data.audio.adaptive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AdaptiveUltrasoundDetectorTest {

    @Test
    public void detector_decodesEmitterWaveform() {
        UltrasoundSessionConfig config = UltrasoundSessionConfig.builder(18200, 18600)
                .symbolDurationMs(40)
                .repeatCount(3)
                .preamble("10101010")
                .thresholdMultiplier(1.5f)
                .build();

        AdaptiveUltrasoundEmitter emitter = new AdaptiveUltrasoundEmitter(config);
        AdaptiveUltrasoundDetector detector = new AdaptiveUltrasoundDetector(config);

        short[] waveform = emitter.buildWaveform(42, 8);
        AdaptiveUltrasoundDetector.DetectionResult result = detector.detect(waveform, 8, 10.0);

        assertTrue(result.isValid());
        assertEquals(42, result.getDecodedToken());
        assertTrue(result.getConfidence() > 0.5f);
    }

    @Test
    public void profiler_picksStrongestFrequencies() {
        int sampleRate = 48000;
        int toneDurationMs = 40;
        int samplesPerTone = (sampleRate * toneDurationMs) / 1000;
        java.util.List<Integer> frequencies = java.util.Arrays.asList(17000, 18200, 18600);
        short[] pcm = new short[samplesPerTone * frequencies.size()];

        fillTone(pcm, 0, samplesPerTone, sampleRate, 17000, 0.1);
        fillTone(pcm, samplesPerTone, samplesPerTone, sampleRate, 18200, 0.7);
        fillTone(pcm, samplesPerTone * 2, samplesPerTone, sampleRate, 18600, 0.5);

        UltrasoundProfiler.ProfileResult result = UltrasoundProfiler.analyseSweep(
                pcm, sampleRate, frequencies, toneDurationMs);

        assertEquals(2, result.getTopFrequencies().size());
        assertEquals(Integer.valueOf(18200), result.getTopFrequencies().get(0));
    }

    private static void fillTone(short[] dest,
                                 int offset,
                                 int count,
                                 int sampleRate,
                                 int frequency,
                                 double amplitudeScale) {
        double amplitude = amplitudeScale * Short.MAX_VALUE;
        double angular = 2.0 * Math.PI * frequency / sampleRate;
        for (int i = 0; i < count; i++) {
            dest[offset + i] = (short) (Math.sin(angular * i) * amplitude);
        }
    }
}
