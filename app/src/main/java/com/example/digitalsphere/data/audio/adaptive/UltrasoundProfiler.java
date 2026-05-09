package com.example.digitalsphere.data.audio.adaptive;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sweep analyzer that ranks candidate frequencies by measured SNR.
 *
 * <p>This class is intentionally pure Java so the profiling logic can be unit
 * tested independently of Android's microphone APIs. The Android capture layer
 * can record PCM and feed it here.</p>
 */
public final class UltrasoundProfiler {

    public static final int SWEEP_START_HZ = 17000;
    public static final int SWEEP_END_HZ = 20500;
    public static final int SWEEP_STEP_HZ = 200;
    public static final int DEFAULT_TONE_DURATION_MS = 40;

    private UltrasoundProfiler() {}

    public static List<Integer> buildSweepFrequencies() {
        List<Integer> frequencies = new ArrayList<>();
        for (int hz = SWEEP_START_HZ; hz <= SWEEP_END_HZ; hz += SWEEP_STEP_HZ) {
            frequencies.add(hz);
        }
        return frequencies;
    }

    public static ProfileResult analyseSweep(short[] pcm,
                                             int sampleRate,
                                             List<Integer> sweepFrequencies,
                                             int toneDurationMs) {
        if (pcm == null || pcm.length == 0 || sampleRate <= 0 || sweepFrequencies == null || sweepFrequencies.isEmpty()) {
            return new ProfileResult(Collections.emptyList(), Collections.emptyMap(), 0.0);
        }

        int samplesPerTone = Math.max(1, (sampleRate * toneDurationMs) / 1000);
        Map<Integer, Double> snrMap = new LinkedHashMap<>();
        double totalNoiseFloor = 0.0;
        int noiseCount = 0;

        for (int i = 0; i < sweepFrequencies.size(); i++) {
            int offset = i * samplesPerTone;
            if (offset + samplesPerTone > pcm.length) break;

            int frequency = sweepFrequencies.get(i);
            double energy = FrequencyEnergyAnalyzer.goertzel(pcm, offset, samplesPerTone, sampleRate, frequency);
            double noiseFloor = estimateNoiseFloor(pcm, offset, samplesPerTone);
            totalNoiseFloor += noiseFloor;
            noiseCount++;

            double snr = noiseFloor > 1e-9 ? energy / noiseFloor : 0.0;
            snrMap.put(frequency, snr);
        }

        List<Integer> top = pickTopFrequencies(snrMap, 2);
        double averageNoiseFloor = noiseCount > 0 ? totalNoiseFloor / noiseCount : 0.0;
        return new ProfileResult(top, snrMap, averageNoiseFloor);
    }

    static List<Integer> pickTopFrequencies(Map<Integer, Double> snrMap, int count) {
        if (snrMap == null || snrMap.isEmpty() || count <= 0) {
            return Collections.emptyList();
        }

        List<Map.Entry<Integer, Double>> entries = new ArrayList<>(snrMap.entrySet());
        entries.sort(Comparator.comparingDouble((Map.Entry<Integer, Double> e) -> e.getValue()).reversed());

        List<Integer> top = new ArrayList<>();
        for (int i = 0; i < entries.size() && i < count; i++) {
            top.add(entries.get(i).getKey());
        }
        return top;
    }

    private static double estimateNoiseFloor(short[] pcm, int offset, int numSamples) {
        int head = Math.max(0, offset - numSamples);
        int tail = Math.min(pcm.length, offset + (numSamples * 2));

        double before = head < offset
                ? FrequencyEnergyAnalyzer.rms(pcm, head, offset - head)
                : 0.0;
        double after = (offset + numSamples) < tail
                ? FrequencyEnergyAnalyzer.rms(pcm, offset + numSamples, tail - (offset + numSamples))
                : 0.0;

        if (before > 0.0 && after > 0.0) {
            return (before + after) / 2.0;
        }
        return Math.max(before, after);
    }

    public static final class ProfileResult {
        private final List<Integer> topFrequencies;
        private final Map<Integer, Double> snrMap;
        private final double noiseFloor;

        ProfileResult(List<Integer> topFrequencies, Map<Integer, Double> snrMap, double noiseFloor) {
            this.topFrequencies = Collections.unmodifiableList(new ArrayList<>(topFrequencies));
            this.snrMap = Collections.unmodifiableMap(new LinkedHashMap<>(snrMap));
            this.noiseFloor = noiseFloor;
        }

        public List<Integer> getTopFrequencies() {
            return topFrequencies;
        }

        public Map<Integer, Double> getSnrMap() {
            return snrMap;
        }

        public double getNoiseFloor() {
            return noiseFloor;
        }
    }
}
