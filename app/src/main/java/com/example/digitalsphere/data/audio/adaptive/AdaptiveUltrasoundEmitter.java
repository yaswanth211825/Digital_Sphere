package com.example.digitalsphere.data.audio.adaptive;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/**
 * Chirp (Linear FM) emitter for the adaptive ultrasound attendance system.
 *
 * Each bit is encoded as a linear frequency-modulated chirp sweeping between
 * f0 and f1 over one symbol period:
 *
 *   bit 1 → upchirp:   f0 → f1  (low to high)
 *   bit 0 → downchirp: f1 → f0  (high to low)
 *
 * Chirp vs FSK advantage: matched-filter processing gain = BW × T samples.
 * With f0=17200 Hz, f1=18800 Hz, symbol=40ms at 48 kHz → TBP ≈ 64 → ~18 dB
 * SNR improvement over single-tone Goertzel, making cross-room detection
 * reliable even at low speaker volume.
 *
 * A Hann window is applied to each chirp symbol to suppress inter-symbol
 * interference and reduce spectral sidelobes.
 */
public class AdaptiveUltrasoundEmitter {

    private static final double AMPLITUDE = 0.55 * Short.MAX_VALUE;

    private final UltrasoundSessionConfig config;
    private final UltrasoundFrameCodec codec = new UltrasoundFrameCodec();

    public AdaptiveUltrasoundEmitter(UltrasoundSessionConfig config) {
        this.config = config;
    }

    /**
     * Builds the complete PCM waveform for one full repeated frame
     * (preamble + token bits + CRC, repeated repeatCount times).
     *
     * @param token    4-bit session token (0–15)
     * @param dataBits number of data bits (must be 4)
     * @return signed 16-bit PCM samples at config.getSampleRate()
     */
    public short[] buildWaveform(int token, int dataBits) {
        boolean[] bits = codec.encodeToken(token, dataBits, config);
        int samplesPerSymbol = config.getSamplesPerSymbol();
        short[] pcm = new short[bits.length * samplesPerSymbol];

        for (int i = 0; i < bits.length; i++) {
            // bit 1 = upchirp (f0→f1), bit 0 = downchirp (f1→f0)
            short[] symbol = generateChirp(bits[i], samplesPerSymbol, config.getSampleRate());
            System.arraycopy(symbol, 0, pcm, i * samplesPerSymbol, samplesPerSymbol);
        }
        return pcm;
    }

    /** Plays one full frame on the speaker (blocking, for background-thread use). */
    public void playOnce(int token, int dataBits) {
        short[] waveform = buildWaveform(token, dataBits);
        int bufferBytes = waveform.length * 2;

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_UNKNOWN)
                        .setContentType(AudioAttributes.CONTENT_TYPE_UNKNOWN)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(config.getSampleRate())
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();

        try {
            track.write(waveform, 0, waveform.length);
            track.play();
            while (track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                if (track.getPlaybackHeadPosition() >= waveform.length) break;
                try {
                    Thread.sleep(10L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            try { track.stop(); } catch (IllegalStateException ignored) {}
            track.release();
        }
    }

    /**
     * Generates one Hann-windowed linear FM chirp symbol.
     *
     * Instantaneous frequency at sample i:
     *   f(i) = fStart + (fEnd - fStart) * i / N
     *
     * Accumulated phase (exact, avoids floating-point drift):
     *   phi(i) = 2π/fs * (fStart*i + (fEnd-fStart)*i² / (2*N))
     *
     * @param upward true → upchirp f0→f1 (bit 1), false → downchirp f1→f0 (bit 0)
     */
    private short[] generateChirp(boolean upward, int n, int sampleRate) {
        int fStart = upward ? config.getF0() : config.getF1();
        int fEnd   = upward ? config.getF1() : config.getF0();

        short[] buffer = new short[n];
        double slope = (double)(fEnd - fStart) / n; // Hz per sample

        for (int i = 0; i < n; i++) {
            double phase = 2.0 * Math.PI * (fStart * i + slope * i * i * 0.5) / sampleRate;
            double hann  = 0.5 * (1.0 - Math.cos(2.0 * Math.PI * i / (n - 1)));
            buffer[i] = (short) (Math.sin(phase) * AMPLITUDE * hann);
        }
        return buffer;
    }
}
