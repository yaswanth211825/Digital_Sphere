package com.example.digitalsphere.data.audio.adaptive;

/**
 * Chirp matched-filter detector for the adaptive ultrasound attendance system.
 *
 * Each symbol window is correlated against two pre-computed reference templates:
 *
 *   refUp   → upchirp   f0→f1  (expected output for bit 1)
 *   refDown → downchirp f1→f0  (expected output for bit 0)
 *
 * The bit decision is: bit = (corrUp > corrDown)
 *
 * Because an upchirp and downchirp are nearly orthogonal signals, their
 * cross-correlation is close to zero. The matched filter naturally separates
 * them without needing an explicit noise-floor threshold or adaptive margin.
 *
 * Processing gain: TBP = (f1-f0) × symbolSeconds ≈ 1600 × 0.040 = 64 → ~18 dB
 * improvement over single-tone Goertzel, making detection reliable across
 * different Android OEM microphone frequency responses.
 *
 * Templates are pre-computed in the constructor (once per session) and are
 * normalized to unit energy so that the dot product values are on the same
 * scale as the PCM amplitude, making the SNR ratio meaningful.
 */
public class AdaptiveUltrasoundDetector {

    private final UltrasoundSessionConfig config;
    private final UltrasoundFrameCodec codec = new UltrasoundFrameCodec();
    private final UltrasoundConfidenceEngine confidenceEngine = new UltrasoundConfidenceEngine();

    /** Normalized upchirp template — matched filter reference for bit 1 (f0→f1). */
    private final float[] refUp;
    /** Normalized downchirp template — matched filter reference for bit 0 (f1→f0). */
    private final float[] refDown;

    public AdaptiveUltrasoundDetector(UltrasoundSessionConfig config) {
        this.config = config;
        int n = config.getSamplesPerSymbol();
        refUp   = buildChirpTemplate(config.getF0(), config.getF1(), n, config.getSampleRate());
        refDown = buildChirpTemplate(config.getF1(), config.getF0(), n, config.getSampleRate());
    }

    /**
     * Decode a PCM window into a token via chirp matched filtering.
     *
     * @param pcm        raw 16-bit PCM samples (length = symbolCount × samplesPerSymbol)
     * @param dataBits   number of data bits to decode (4 for token)
     * @param noiseFloor RMS amplitude of quiet background (used for SNR estimation)
     */
    public DetectionResult detect(short[] pcm, int dataBits, double noiseFloor) {
        if (pcm == null || pcm.length == 0) {
            return DetectionResult.invalid();
        }

        int samplesPerSymbol = config.getSamplesPerSymbol();
        int symbolCount = pcm.length / samplesPerSymbol;
        if (symbolCount <= 0) {
            return DetectionResult.invalid();
        }

        boolean[] bits = new boolean[symbolCount];
        double snrAccumulator = 0.0;
        float reliabilityAccumulator = 0.0f;

        for (int symbol = 0; symbol < symbolCount; symbol++) {
            int offset = symbol * samplesPerSymbol;

            // Matched filter: correlate window against each chirp template.
            // Templates are unit-energy normalized → dot product is in PCM amplitude units.
            double corrUp   = dotProduct(pcm, offset, refUp,   samplesPerSymbol);
            double corrDown = dotProduct(pcm, offset, refDown, samplesPerSymbol);

            // Bit decision: upchirp wins → bit 1, downchirp wins → bit 0.
            // No threshold needed — chirps are orthogonal so difference is clear.
            bits[symbol] = corrUp > corrDown;

            // SNR: winning correlation energy vs noise floor
            double winner = Math.max(corrUp, corrDown);
            snrAccumulator += Math.max(1.0, winner) / Math.max(1.0, noiseFloor);

            // Symbol reliability: how decisively one chirp won over the other.
            // 0 = ambiguous (equal), 1 = one clearly dominates.
            double diff  = Math.abs(corrUp - corrDown);
            double total = Math.abs(corrUp) + Math.abs(corrDown) + 1e-9;
            reliabilityAccumulator += (float) Math.min(1.0, diff / total);
        }

        UltrasoundFrameCodec.DecodeResult decodeResult = codec.decode(bits, dataBits, config);
        double avgSnr           = snrAccumulator / symbolCount;
        float symbolReliability = reliabilityAccumulator / symbolCount;

        float confidence = confidenceEngine.score(
                avgSnr,
                decodeResult.getRepeatAgreement(),
                decodeResult.isValid(),
                symbolReliability);

        return new DetectionResult(
                decodeResult.getDecodedToken(),
                decodeResult.isValid(),
                confidence,
                avgSnr,
                decodeResult.getRepeatAgreement());
    }

    // ── Template builder ────────────────────────────────────────────────────

    /**
     * Builds a Hann-windowed linear FM chirp template normalized to unit energy.
     *
     * Phase formula (exact, avoids accumulation drift):
     *   phi(i) = 2π/fs × (fStart×i + (fEnd-fStart)×i² / (2×N))
     */
    private static float[] buildChirpTemplate(int fStart, int fEnd, int n, int sampleRate) {
        float[] template = new float[n];
        double slope = (double)(fEnd - fStart) / n;

        for (int i = 0; i < n; i++) {
            double phase = 2.0 * Math.PI * (fStart * i + slope * i * i * 0.5) / sampleRate;
            double hann  = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (n - 1)));
            template[i]  = (float)(Math.sin(phase) * hann);
        }

        // Normalize to unit energy so dot-product values are in PCM amplitude units
        float energy = 0f;
        for (float v : template) energy += v * v;
        float norm = (float) Math.sqrt(energy);
        if (norm > 1e-9f) {
            for (int i = 0; i < n; i++) template[i] /= norm;
        }
        return template;
    }

    /** Dot product of PCM samples[offset..offset+length] against a float reference. */
    private static double dotProduct(short[] pcm, int offset, float[] ref, int length) {
        double sum = 0.0;
        for (int i = 0; i < length; i++) {
            sum += pcm[offset + i] * ref[i];
        }
        return sum;
    }

    // ── Result ──────────────────────────────────────────────────────────────

    public static final class DetectionResult {
        private final int decodedToken;
        private final boolean valid;
        private final float confidence;
        private final double averageSnr;
        private final float repeatAgreement;

        DetectionResult(int decodedToken,
                        boolean valid,
                        float confidence,
                        double averageSnr,
                        float repeatAgreement) {
            this.decodedToken    = decodedToken;
            this.valid           = valid;
            this.confidence      = confidence;
            this.averageSnr      = averageSnr;
            this.repeatAgreement = repeatAgreement;
        }

        static DetectionResult invalid() {
            return new DetectionResult(-1, false, 0f, 0.0, 0f);
        }

        public int   getDecodedToken()    { return decodedToken; }
        public boolean isValid()          { return valid; }
        public float getConfidence()      { return confidence; }
        public double getAverageSnr()     { return averageSnr; }
        public float getRepeatAgreement() { return repeatAgreement; }
    }
}
