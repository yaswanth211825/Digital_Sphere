package com.example.digitalsphere.domain.verification;

/**
 * Immutable snapshot of all sensor readings collected for a single
 * verification attempt.
 *
 * <p>A {@code SignalReading} is the <b>sole input</b> to
 * {@link DsvfAlgorithm#evaluate(SignalReading)}. It encapsulates every
 * raw measurement the data layer can provide — BLE RSSI, barometric
 * pressure, ultrasound token/confidence, and ambient audio hashes — so
 * the algorithm never reaches into the Android data layer directly.</p>
 *
 * <h3>Why a Builder?</h3>
 * The class has 13+ fields. A telescoping constructor would be unreadable
 * and error-prone. The Builder pattern lets the presenter set whichever
 * signals are available and leave the rest at safe defaults (−1, false, 0,
 * null) which the algorithm interprets as "unavailable".
 *
 * <h3>Thread safety</h3>
 * The object is fully immutable once built. The {@code float[]} arrays
 * are defensively copied in the builder and in the getters, so no caller
 * can mutate the internal state.
 *
 * <p>Pure Java — no Android imports.</p>
 */
public final class SignalReading {

    // ── BLE fields ──────────────────────────────────────────────────────

    /** Average RSSI in dBm over the sampling window (e.g. −65). */
    private final int rssiAverage;

    /** Number of BLE RSSI samples averaged. 0 = BLE unavailable. */
    private final int bleRssiSampleCount;

    // ── Barometer fields ────────────────────────────────────────────────

    /** {@code true} if the device has a barometric pressure sensor. */
    private final boolean barometerAvailable;

    /** Student's barometric pressure in hPa (e.g. 1013.25). */
    private final float studentPressureHPa;

    /** Professor's barometric pressure in hPa, received via BLE. */
    private final float professorPressureHPa;

    /**
     * Variance (σ²) of the student's barometer readings over the
     * sampling window, in hPa². High variance indicates sensor noise
     * or the student moving between floors during the reading.
     */
    private final float pressureVarianceHPa;

    // ── Ultrasound fields ───────────────────────────────────────────────

    /**
     * The 4-bit OOK token decoded by the student's mic.
     * −1 if no token was detected (silence / no signal).
     */
    private final int detectedUltrasoundToken;

    /**
     * The expected 4-bit token derived from the session ID.
     * −1 if the session has no ultrasound component.
     */
    private final int expectedUltrasoundToken;

    /**
     * Goertzel magnitude normalised to [0.0, 1.0].
     * 0.0 = silence, 1.0 = maximum possible detection confidence.
     */
    private final float ultrasoundConfidence;

    // ── Audio correlation fields ────────────────────────────────────────

    /**
     * Student's 8-band ambient audio fingerprint (RMS per band).
     * {@code null} if audio recording was not available.
     */
    private final float[] studentAmbientHash;

    /**
     * Professor's 8-band ambient audio fingerprint, received via BLE.
     * {@code null} if the professor did not broadcast a hash.
     */
    private final float[] professorAmbientHash;

    /**
     * Estimated signal-to-noise ratio of the student's audio recording,
     * in dB. Higher is better. 0.0 if unknown or not computed.
     */
    private final float audioSnrEstimate;

    // ── Metadata ────────────────────────────────────────────────────────

    /** Wall-clock time when this reading was assembled (epoch ms). */
    private final long timestampMs;

    // ── Private constructor (use Builder) ───────────────────────────────

    private SignalReading(Builder b) {
        this.rssiAverage            = b.rssiAverage;
        this.bleRssiSampleCount     = b.bleRssiSampleCount;
        this.barometerAvailable     = b.barometerAvailable;
        this.studentPressureHPa     = b.studentPressureHPa;
        this.professorPressureHPa   = b.professorPressureHPa;
        this.pressureVarianceHPa    = b.pressureVarianceHPa;
        this.detectedUltrasoundToken  = b.detectedUltrasoundToken;
        this.expectedUltrasoundToken  = b.expectedUltrasoundToken;
        this.ultrasoundConfidence   = b.ultrasoundConfidence;
        this.studentAmbientHash     = copyOrNull(b.studentAmbientHash);
        this.professorAmbientHash   = copyOrNull(b.professorAmbientHash);
        this.audioSnrEstimate       = b.audioSnrEstimate;
        this.timestampMs            = b.timestampMs;
    }

    // ── Getters ─────────────────────────────────────────────────────────

    public int     getRssiAverage()            { return rssiAverage; }
    public int     getBleRssiSampleCount()     { return bleRssiSampleCount; }
    public boolean isBarometerAvailable()       { return barometerAvailable; }
    public float   getStudentPressureHPa()     { return studentPressureHPa; }
    public float   getProfessorPressureHPa()   { return professorPressureHPa; }
    public float   getPressureVarianceHPa()    { return pressureVarianceHPa; }
    public int     getDetectedUltrasoundToken() { return detectedUltrasoundToken; }
    public int     getExpectedUltrasoundToken() { return expectedUltrasoundToken; }
    public float   getUltrasoundConfidence()   { return ultrasoundConfidence; }
    public float   getAudioSnrEstimate()       { return audioSnrEstimate; }
    public long    getTimestampMs()            { return timestampMs; }

    /**
     * Returns a defensive copy of the student's ambient audio hash.
     * @return float[8] copy, or {@code null} if audio was unavailable.
     */
    public float[] getStudentAmbientHash() {
        return copyOrNull(studentAmbientHash);
    }

    /**
     * Returns a defensive copy of the professor's ambient audio hash.
     * @return float[8] copy, or {@code null} if not received.
     */
    public float[] getProfessorAmbientHash() {
        return copyOrNull(professorAmbientHash);
    }

    // ── Convenience queries ─────────────────────────────────────────────

    /** {@code true} if at least one BLE RSSI sample was collected. */
    public boolean isBleAvailable() {
        return bleRssiSampleCount > 0;
    }

    /** {@code true} if a valid ultrasound token was decoded. */
    public boolean isUltrasoundAvailable() {
        return detectedUltrasoundToken >= 0 && expectedUltrasoundToken >= 0;
    }

    /** {@code true} if both ambient audio hashes are present. */
    public boolean isAudioAvailable() {
        return studentAmbientHash != null && professorAmbientHash != null;
    }

    // ── Internal helper ─────────────────────────────────────────────────

    private static float[] copyOrNull(float[] src) {
        if (src == null) return null;
        float[] copy = new float[src.length];
        System.arraycopy(src, 0, copy, 0, src.length);
        return copy;
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Builder
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Mutable builder for {@link SignalReading}.
     *
     * <p>All fields default to "unavailable" sentinel values:
     * integers default to −1 or 0, booleans to {@code false}, arrays to
     * {@code null}. The presenter sets only the fields for which sensor
     * data was actually collected.</p>
     *
     * <pre>{@code
     * SignalReading reading = new SignalReading.Builder()
     *         .rssiAverage(-62)
     *         .bleRssiSampleCount(15)
     *         .barometerAvailable(true)
     *         .studentPressureHPa(1013.2f)
     *         .professorPressureHPa(1013.3f)
     *         .pressureVarianceHPa(0.01f)
     *         .detectedUltrasoundToken(7)
     *         .expectedUltrasoundToken(7)
     *         .ultrasoundConfidence(0.92f)
     *         .studentAmbientHash(studentHash)
     *         .professorAmbientHash(profHash)
     *         .audioSnrEstimate(18.5f)
     *         .build();
     * }</pre>
     */
    public static final class Builder {

        // Defaults: "unavailable" sentinels
        private int     rssiAverage            = -100;
        private int     bleRssiSampleCount     = 0;
        private boolean barometerAvailable      = false;
        private float   studentPressureHPa     = 0f;
        private float   professorPressureHPa   = 0f;
        private float   pressureVarianceHPa    = 0f;
        private int     detectedUltrasoundToken  = -1;
        private int     expectedUltrasoundToken  = -1;
        private float   ultrasoundConfidence   = 0f;
        private float[] studentAmbientHash     = null;
        private float[] professorAmbientHash   = null;
        private float   audioSnrEstimate       = 0f;
        private long    timestampMs            = System.currentTimeMillis();

        public Builder() {}

        public Builder rssiAverage(int val)              { this.rssiAverage = val;             return this; }
        public Builder bleRssiSampleCount(int val)       { this.bleRssiSampleCount = val;      return this; }
        public Builder barometerAvailable(boolean val)    { this.barometerAvailable = val;       return this; }
        public Builder studentPressureHPa(float val)     { this.studentPressureHPa = val;      return this; }
        public Builder professorPressureHPa(float val)   { this.professorPressureHPa = val;    return this; }
        public Builder pressureVarianceHPa(float val)    { this.pressureVarianceHPa = val;     return this; }
        public Builder detectedUltrasoundToken(int val)  { this.detectedUltrasoundToken = val;   return this; }
        public Builder expectedUltrasoundToken(int val)  { this.expectedUltrasoundToken = val;   return this; }
        public Builder ultrasoundConfidence(float val)   { this.ultrasoundConfidence = val;    return this; }
        public Builder audioSnrEstimate(float val)       { this.audioSnrEstimate = val;        return this; }
        public Builder timestampMs(long val)             { this.timestampMs = val;             return this; }

        /**
         * Sets the student's ambient audio hash (defensive copy taken).
         * @param hash float[8] from {@code AmbientAudioRecorder.computeHash()},
         *             or {@code null} if audio was unavailable.
         */
        public Builder studentAmbientHash(float[] hash) {
            this.studentAmbientHash = copyOrNull(hash);
            return this;
        }

        /**
         * Sets the professor's ambient audio hash (defensive copy taken).
         * @param hash float[8] received via BLE extended advertising,
         *             or {@code null} if not received.
         */
        public Builder professorAmbientHash(float[] hash) {
            this.professorAmbientHash = copyOrNull(hash);
            return this;
        }

        /**
         * Builds an immutable {@link SignalReading}.
         * @return a new {@code SignalReading} with defensively-copied arrays.
         */
        public SignalReading build() {
            return new SignalReading(this);
        }
    }
}
