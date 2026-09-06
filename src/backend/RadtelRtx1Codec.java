package org.aprsdroid.app;

import java.util.Arrays;

/**
 * Host-to-radio debug envelope for the RT-950 Pro V0.29 RTX1 firmware bridge.
 *
 * Wire format on FFE1:
 *   'R' 'T' 'X' '1' | raw AX.25 frame | CRC-16/X.25 FCS (little-endian)
 *
 * This is deliberately not KISS. The matching debug firmware strips the RTX1
 * header and feeds the raw AX.25+FCS directly into the OEM AX.25 decoder, then
 * reuses the OEM digipeater/beacon RF TX state machine.
 */
public final class RadtelRtx1Codec {
    public static final int MAX_WIRE_LENGTH = 139;
    public static final byte[] MAGIC = new byte[] { 'R', 'T', 'X', '1' };

    private RadtelRtx1Codec() {}

    /** CRC-16/X.25: init FFFF, reflected poly 8408, final complement. */
    public static int crcX25(byte[] data) {
        if (data == null) throw new IllegalArgumentException("AX.25 data is null");
        int crc = 0xffff;
        for (byte value : data) {
            crc ^= value & 0xff;
            for (int bit = 0; bit < 8; bit++) {
                if ((crc & 1) != 0) crc = (crc >>> 1) ^ 0x8408;
                else crc >>>= 1;
            }
        }
        return (~crc) & 0xffff;
    }

    public static byte[] encode(byte[] ax25) {
        if (ax25 == null || ax25.length == 0)
            throw new IllegalArgumentException("AX.25 frame is empty");

        final int wireLength = MAGIC.length + ax25.length + 2;
        if (wireLength > MAX_WIRE_LENGTH)
            throw new IllegalArgumentException(
                    "RT950 RTX1 frame too long: " + wireLength + " > " + MAX_WIRE_LENGTH);

        byte[] out = new byte[wireLength];
        System.arraycopy(MAGIC, 0, out, 0, MAGIC.length);
        System.arraycopy(ax25, 0, out, MAGIC.length, ax25.length);

        int fcs = crcX25(ax25);
        int p = MAGIC.length + ax25.length;
        out[p] = (byte)(fcs & 0xff);
        out[p + 1] = (byte)((fcs >>> 8) & 0xff);
        return out;
    }

    public static boolean hasMagic(byte[] wire) {
        return wire != null && wire.length >= MAGIC.length &&
                Arrays.equals(MAGIC, Arrays.copyOfRange(wire, 0, MAGIC.length));
    }
}
