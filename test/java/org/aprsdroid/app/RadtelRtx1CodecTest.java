package org.aprsdroid.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class RadtelRtx1CodecTest {
    private static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++)
            out[i] = (byte)Integer.parseInt(parts[i], 16);
        return out;
    }

    @Test public void crcX25MatchesStandardCheckValue() {
        assertEquals(0x906e,
                RadtelRtx1Codec.crcX25("123456789".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test public void realRt950FrameGetsExpectedFcsAndEnvelope() {
        byte[] ax25 = hex(
                "82 A0 82 A8 70 62 E2 96 8A 64 84 A6 88 6E " +
                "AE 92 88 8A 62 40 62 AE 92 88 8A 64 40 63 03 F0 " +
                "21 33 34 31 32 2E 37 33 4E 2F 31 30 38 34 39 2E " +
                "3A 30 45 26 30 35 33 2F 30 30 30 2F 41 3D 30 30 " +
                "30 30 30 30 41 50 52 53 43 4E 20 57 49 46 49 20 " +
                "34 2E 33 30 56");

        assertEquals(83, ax25.length);
        assertEquals(0x000b, RadtelRtx1Codec.crcX25(ax25));

        byte[] wire = RadtelRtx1Codec.encode(ax25);
        assertEquals(89, wire.length);
        assertTrue(RadtelRtx1Codec.hasMagic(wire));
        assertArrayEquals(new byte[] { 'R', 'T', 'X', '1' },
                java.util.Arrays.copyOfRange(wire, 0, 4));
        assertEquals(0x0b, wire[wire.length - 2] & 0xff);
        assertEquals(0x00, wire[wire.length - 1] & 0xff);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWireFramesBeyondFirmwareBuffer() {
        RadtelRtx1Codec.encode(new byte[RadtelRtx1Codec.MAX_WIRE_LENGTH]);
    }
}
