package com.example.digitalsphere.data.audio.adaptive;

import android.media.MediaRecorder;

/**
 * Immutable runtime capability snapshot for the near-ultrasound path.
 *
 * <p>Android exposes near-ultrasound support as device properties instead of
 * guaranteeing it on every handset. This object keeps the probing result in a
 * test-friendly form and drives adaptive decisions elsewhere in the stack.</p>
 */
public final class UltrasoundCapabilities {

    public enum RecommendedAudioSource {
        UNPROCESSED,
        VOICE_RECOGNITION,
        MIC
    }

    private final boolean micNearUltrasound;
    private final boolean speakerNearUltrasound;
    private final boolean unprocessedSupported;
    private final boolean ultrasoundWeak;
    private final RecommendedAudioSource recommendedAudioSource;

    UltrasoundCapabilities(boolean micNearUltrasound,
                           boolean speakerNearUltrasound,
                           boolean unprocessedSupported,
                           RecommendedAudioSource recommendedAudioSource) {
        this.micNearUltrasound = micNearUltrasound;
        this.speakerNearUltrasound = speakerNearUltrasound;
        this.unprocessedSupported = unprocessedSupported;
        this.recommendedAudioSource = recommendedAudioSource != null
                ? recommendedAudioSource
                : RecommendedAudioSource.MIC;
        this.ultrasoundWeak = !micNearUltrasound || !speakerNearUltrasound;
    }

    public boolean isMicNearUltrasound() {
        return micNearUltrasound;
    }

    public boolean isSpeakerNearUltrasound() {
        return speakerNearUltrasound;
    }

    public boolean isUnprocessedSupported() {
        return unprocessedSupported;
    }

    public boolean isUltrasoundWeak() {
        return ultrasoundWeak;
    }

    public RecommendedAudioSource getRecommendedAudioSource() {
        return recommendedAudioSource;
    }

    public int getRecommendedAudioSourceConstant() {
        switch (recommendedAudioSource) {
            case UNPROCESSED:
                return MediaRecorder.AudioSource.UNPROCESSED;
            case VOICE_RECOGNITION:
                return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            case MIC:
            default:
                return MediaRecorder.AudioSource.MIC;
        }
    }

    @Override
    public String toString() {
        return "UltrasoundCapabilities{"
                + "micNearUltrasound=" + micNearUltrasound
                + ", speakerNearUltrasound=" + speakerNearUltrasound
                + ", unprocessedSupported=" + unprocessedSupported
                + ", ultrasoundWeak=" + ultrasoundWeak
                + ", recommendedAudioSource=" + recommendedAudioSource
                + '}';
    }
}
