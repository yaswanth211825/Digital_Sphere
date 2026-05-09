package com.example.digitalsphere.data.audio.adaptive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UltrasoundCapabilityManagerTest {

    @Test
    public void recommendAudioSource_prefersUnprocessedWhenSupported() {
        assertEquals(
                UltrasoundCapabilities.RecommendedAudioSource.UNPROCESSED,
                UltrasoundCapabilityManager.recommendAudioSource(true, true, true));
    }

    @Test
    public void recommendAudioSource_fallsBackToVoiceRecognitionWhenNearUltrasoundExists() {
        assertEquals(
                UltrasoundCapabilities.RecommendedAudioSource.VOICE_RECOGNITION,
                UltrasoundCapabilityManager.recommendAudioSource(true, false, false));
    }

    @Test
    public void recommendAudioSource_usesMicWhenNoSpecialSupportExists() {
        assertEquals(
                UltrasoundCapabilities.RecommendedAudioSource.MIC,
                UltrasoundCapabilityManager.recommendAudioSource(false, false, false));
    }
}
