package com.example.digitalsphere.data.audio.adaptive;

import android.content.Context;
import android.media.AudioManager;

/**
 * Runtime capability probe for Android near-ultrasound support.
 */
public final class UltrasoundCapabilityManager {

    private UltrasoundCapabilityManager() {}

    public static UltrasoundCapabilities probe(Context context) {
        AudioManager audioManager = context != null
                ? (AudioManager) context.getSystemService(Context.AUDIO_SERVICE)
                : null;

        boolean micNearUltrasound = parseBooleanProperty(audioManager,
                AudioManager.PROPERTY_SUPPORT_MIC_NEAR_ULTRASOUND);
        boolean speakerNearUltrasound = parseBooleanProperty(audioManager,
                AudioManager.PROPERTY_SUPPORT_SPEAKER_NEAR_ULTRASOUND);
        boolean unprocessedSupported = parseBooleanProperty(audioManager,
                AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);

        return new UltrasoundCapabilities(
                micNearUltrasound,
                speakerNearUltrasound,
                unprocessedSupported,
                recommendAudioSource(micNearUltrasound, speakerNearUltrasound, unprocessedSupported));
    }

    static UltrasoundCapabilities.RecommendedAudioSource recommendAudioSource(
            boolean micNearUltrasound,
            boolean speakerNearUltrasound,
            boolean unprocessedSupported) {
        if (unprocessedSupported) {
            return UltrasoundCapabilities.RecommendedAudioSource.UNPROCESSED;
        }
        if (micNearUltrasound || speakerNearUltrasound) {
            return UltrasoundCapabilities.RecommendedAudioSource.VOICE_RECOGNITION;
        }
        return UltrasoundCapabilities.RecommendedAudioSource.MIC;
    }

    private static boolean parseBooleanProperty(AudioManager audioManager, String propertyName) {
        if (audioManager == null || propertyName == null || propertyName.isEmpty()) {
            return false;
        }

        String value;
        try {
            value = audioManager.getProperty(propertyName);
        } catch (Throwable ignored) {
            return false;
        }

        return value != null && Boolean.parseBoolean(value);
    }
}
