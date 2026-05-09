package com.example.digitalsphere.data.audio.adaptive;

import java.util.ArrayList;
import java.util.List;

/**
 * FSK framing codec with preamble, CRC-8, repetition, and majority voting.
 */
public final class UltrasoundFrameCodec {

    public static final int CRC_BITS = 8;

    public boolean[] encodeToken(int token, int dataBits, UltrasoundSessionConfig config) {
        if (dataBits <= 0 || dataBits > 16) {
            throw new IllegalArgumentException("dataBits must be in [1, 16].");
        }

        boolean[] payload = intToBits(token, dataBits);
        boolean[] frame = buildSingleFrame(payload, config);

        boolean[] repeated = new boolean[frame.length * config.getRepeatCount()];
        for (int i = 0; i < config.getRepeatCount(); i++) {
            System.arraycopy(frame, 0, repeated, i * frame.length, frame.length);
        }
        return repeated;
    }

    public DecodeResult decode(boolean[] observedBits, int dataBits, UltrasoundSessionConfig config) {
        if (observedBits == null || observedBits.length == 0) {
            return DecodeResult.invalid();
        }

        boolean[] preambleBits = preambleToBits(config.getPreamble());
        int singleFrameLength = preambleBits.length + dataBits + CRC_BITS;
        int frameStart = findPreamble(observedBits, preambleBits);
        if (frameStart < 0) {
            return DecodeResult.invalid();
        }

        List<boolean[]> framePayloads = new ArrayList<>();
        List<boolean[]> frameCrcs = new ArrayList<>();

        int cursor = frameStart;
        while (cursor + singleFrameLength <= observedBits.length && framePayloads.size() < config.getRepeatCount()) {
            if (!matchesPreamble(observedBits, cursor, preambleBits)) {
                break;
            }

            int payloadStart = cursor + preambleBits.length;
            boolean[] payload = slice(observedBits, payloadStart, dataBits);
            boolean[] crcBits = slice(observedBits, payloadStart + dataBits, CRC_BITS);
            framePayloads.add(payload);
            frameCrcs.add(crcBits);
            cursor += singleFrameLength;
        }

        if (framePayloads.isEmpty()) {
            return DecodeResult.invalid();
        }

        boolean[] votedPayload = majorityVote(framePayloads);
        boolean[] votedCrc = majorityVote(frameCrcs);
        boolean crcValid = crcMatches(votedPayload, votedCrc);
        float repeatAgreement = computeRepeatAgreement(framePayloads, votedPayload);
        int token = bitsToInt(votedPayload);

        return new DecodeResult(token, crcValid, repeatAgreement, framePayloads.size());
    }

    private boolean[] buildSingleFrame(boolean[] payload, UltrasoundSessionConfig config) {
        boolean[] preambleBits = preambleToBits(config.getPreamble());
        boolean[] crcBits = intToBits(computeCrc8(payload), CRC_BITS);
        boolean[] frame = new boolean[preambleBits.length + payload.length + crcBits.length];

        System.arraycopy(preambleBits, 0, frame, 0, preambleBits.length);
        System.arraycopy(payload, 0, frame, preambleBits.length, payload.length);
        System.arraycopy(crcBits, 0, frame, preambleBits.length + payload.length, crcBits.length);
        return frame;
    }

    static int computeCrc8(boolean[] payloadBits) {
        int crc = 0x00;
        for (boolean bit : payloadBits) {
            int input = bit ? 1 : 0;
            int mix = ((crc >> 7) & 0x01) ^ input;
            crc = (crc << 1) & 0xFF;
            if (mix != 0) {
                crc ^= 0x07;
            }
        }
        return crc & 0xFF;
    }

    private boolean crcMatches(boolean[] payload, boolean[] observedCrc) {
        return bitsToInt(observedCrc) == computeCrc8(payload);
    }

    private int findPreamble(boolean[] observedBits, boolean[] preambleBits) {
        for (int i = 0; i <= observedBits.length - preambleBits.length; i++) {
            if (hammingDistance(observedBits, i, preambleBits) <= 1) {
                return i;
            }
        }
        return -1;
    }

    private boolean matchesPreamble(boolean[] observedBits, int start, boolean[] preambleBits) {
        if (start < 0 || start + preambleBits.length > observedBits.length) {
            return false;
        }
        return hammingDistance(observedBits, start, preambleBits) <= 1;
    }

    private static int hammingDistance(boolean[] haystack, int start, boolean[] needle) {
        int dist = 0;
        for (int i = 0; i < needle.length; i++) {
            if (haystack[start + i] != needle[i]) dist++;
        }
        return dist;
    }

    private boolean[] majorityVote(List<boolean[]> candidates) {
        int length = candidates.get(0).length;
        boolean[] voted = new boolean[length];
        for (int bit = 0; bit < length; bit++) {
            int ones = 0;
            for (boolean[] candidate : candidates) {
                if (candidate[bit]) ones++;
            }
            voted[bit] = ones * 2 >= candidates.size();
        }
        return voted;
    }

    private float computeRepeatAgreement(List<boolean[]> payloads, boolean[] votedPayload) {
        if (payloads.isEmpty()) return 0f;

        int matches = 0;
        for (boolean[] payload : payloads) {
            boolean identical = true;
            for (int bit = 0; bit < votedPayload.length; bit++) {
                if (payload[bit] != votedPayload[bit]) {
                    identical = false;
                    break;
                }
            }
            if (identical) matches++;
        }
        return (float) matches / payloads.size();
    }

    private static boolean[] slice(boolean[] bits, int start, int length) {
        boolean[] out = new boolean[length];
        System.arraycopy(bits, start, out, 0, length);
        return out;
    }

    static boolean[] intToBits(int value, int bitCount) {
        boolean[] bits = new boolean[bitCount];
        for (int i = 0; i < bitCount; i++) {
            int shift = bitCount - 1 - i;
            bits[i] = ((value >> shift) & 0x01) == 1;
        }
        return bits;
    }

    static int bitsToInt(boolean[] bits) {
        int value = 0;
        for (boolean bit : bits) {
            value = (value << 1) | (bit ? 1 : 0);
        }
        return value;
    }

    static boolean[] preambleToBits(String preamble) {
        boolean[] bits = new boolean[preamble.length()];
        for (int i = 0; i < preamble.length(); i++) {
            bits[i] = preamble.charAt(i) == '1';
        }
        return bits;
    }

    public static final class DecodeResult {
        private final int decodedToken;
        private final boolean valid;
        private final float repeatAgreement;
        private final int decodedRepeats;

        DecodeResult(int decodedToken, boolean valid, float repeatAgreement, int decodedRepeats) {
            this.decodedToken = decodedToken;
            this.valid = valid;
            this.repeatAgreement = repeatAgreement;
            this.decodedRepeats = decodedRepeats;
        }

        static DecodeResult invalid() {
            return new DecodeResult(-1, false, 0f, 0);
        }

        public int getDecodedToken() {
            return decodedToken;
        }

        public boolean isValid() {
            return valid;
        }

        public float getRepeatAgreement() {
            return repeatAgreement;
        }

        public int getDecodedRepeats() {
            return decodedRepeats;
        }
    }
}
