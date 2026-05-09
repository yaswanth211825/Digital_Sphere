package com.example.digitalsphere.data.audio.adaptive;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UltrasoundFrameCodecTest {

    private final UltrasoundSessionConfig config = UltrasoundSessionConfig.builder(18200, 18600)
            .repeatCount(3)
            .preamble("10101010")
            .build();

    private final UltrasoundFrameCodec codec = new UltrasoundFrameCodec();

    @Test
    public void encodeThenDecode_roundTripsToken() {
        boolean[] bits = codec.encodeToken(42, 8, config);

        UltrasoundFrameCodec.DecodeResult result = codec.decode(bits, 8, config);

        assertTrue(result.isValid());
        assertEquals(42, result.getDecodedToken());
        assertEquals(1.0f, result.getRepeatAgreement(), 0.0001f);
    }

    @Test
    public void decode_crcFailureMarksInvalid() {
        boolean[] bits = codec.encodeToken(42, 8, config);
        int singleFrameLength = config.getPreambleBitCount() + 8 + UltrasoundFrameCodec.CRC_BITS;
        int crcStart = config.getPreambleBitCount() + 8;
        for (int repeat = 0; repeat < config.getRepeatCount(); repeat++) {
            int index = (repeat * singleFrameLength) + crcStart;
            bits[index] = !bits[index];
        }

        UltrasoundFrameCodec.DecodeResult result = codec.decode(bits, 8, config);

        assertFalse(result.isValid());
    }

    @Test
    public void decode_majorityVoteRecoversSingleRepeatCorruption() {
        boolean[] bits = codec.encodeToken(42, 8, config);
        int singleFrameLength = config.getPreambleBitCount() + 8 + UltrasoundFrameCodec.CRC_BITS;

        bits[singleFrameLength + 10] = !bits[singleFrameLength + 10];

        UltrasoundFrameCodec.DecodeResult result = codec.decode(bits, 8, config);

        assertTrue(result.isValid());
        assertEquals(42, result.getDecodedToken());
        assertTrue(result.getRepeatAgreement() < 1.0f);
    }
}
