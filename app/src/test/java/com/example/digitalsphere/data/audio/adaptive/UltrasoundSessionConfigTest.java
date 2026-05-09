package com.example.digitalsphere.data.audio.adaptive;

import org.junit.Test;
import static org.junit.Assert.*;

public class UltrasoundSessionConfigTest {

    @Test
    public void compactBleBytes_roundTripPreservesAdaptiveParameters() {
        UltrasoundSessionConfig config = UltrasoundSessionConfig.builder(18200, 18600)
                .symbolDurationMs(48)
                .repeatCount(4)
                .build();

        byte[] compact = config.toCompactBleBytes();
        UltrasoundSessionConfig restored = UltrasoundSessionConfig.fromCompactBleBytes(compact);

        assertNotNull(compact);
        assertNotNull(restored);
        assertEquals(config.getF0(), restored.getF0());
        assertEquals(config.getF1(), restored.getF1());
        assertEquals(config.getSymbolDurationMs(), restored.getSymbolDurationMs());
        assertEquals(config.getRepeatCount(), restored.getRepeatCount());
    }

    @Test
    public void compactBleBytes_invalidIndexesReturnNull() {
        assertNull(UltrasoundSessionConfig.fromCompactBleBytes(new byte[] {(byte) 0xFF, 1, 40, 3}));
    }
}
