package org.aprsdroid.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class Ax25Tnc2CompatTest {
    private static byte[] hex(String s) {
        String[] parts = s.trim().split("\\s+");
        byte[] out = new byte[parts.length];
        for (int i = 0; i < parts.length; i++)
            out[i] = (byte) Integer.parseInt(parts[i], 16);
        return out;
    }

    @Test public void decodesRealRt950ReceivedBeacon() {
        byte[] frame = hex(
            "82 A0 82 A8 70 62 E2 " +
            "96 8A 64 84 A6 88 6E " +
            "AE 92 88 8A 62 40 62 " +
            "AE 92 88 8A 64 40 63 " +
            "03 F0 " +
            "21 33 34 31 32 2E 37 33 4E 2F 31 30 38 34 39 2E 3A 30 45 26 " +
            "30 35 33 2F 30 30 30 2F 41 3D 30 30 30 30 30 30 " +
            "41 50 52 53 43 4E 20 57 49 46 49 20 34 2E 33 30 56");

        assertEquals(
            "KE2BSD-7>APAT81-1,WIDE1-1,WIDE2-1:" +
            "!3412.73N/10849.:0E&053/000/A=000000APRSCN WIFI 4.30V",
            Ax25Tnc2Compat.decodeUiFrame(frame));
    }

    @Test public void decodesRt950DigipeatedVariantWithReservedBitsPresent() {
        byte[] frame = hex(
            "82 A0 82 A8 70 62 60 " +
            "96 8A 64 84 A6 88 7A " +
            "96 8A 64 84 A6 88 6E " +
            "AE 92 88 8A 62 40 60 " +
            "AE 92 88 8A 64 40 61 " +
            "03 F0 21 54 45 53 54");

        assertEquals(
            "KE2BSD-13>APAT81,KE2BSD-7,WIDE1,WIDE2:!TEST",
            Ax25Tnc2Compat.decodeUiFrame(frame));
    }
}
