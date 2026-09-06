package org.aprsdroid.app;

import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal AX.25 UI-frame to TNC2 converter used as a compatibility fallback
 * for BLE KISS radios such as the Radtel RT-950 Pro.
 *
 * This does not attempt APRS payload parsing. It only accepts a structurally
 * valid AX.25 address list followed by UI control 0x03 and PID 0xF0, then
 * renders source/destination/digipeaters and the information field as TNC2.
 */
public final class Ax25Tnc2Compat {
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");
    private static final int MAX_ADDRESSES = 10;

    private Ax25Tnc2Compat() {}

    private static final class Address {
        final String call;
        final boolean repeated;

        Address(String call, boolean repeated) {
            this.call = call;
            this.repeated = repeated;
        }
    }

    public static String decodeUiFrame(byte[] frame) {
        if (frame == null || frame.length < 16)
            throw new IllegalArgumentException("AX.25 frame too short");

        List<Address> addresses = new ArrayList<Address>();
        int pos = 0;
        boolean last = false;

        while (!last) {
            if (addresses.size() >= MAX_ADDRESSES)
                throw new IllegalArgumentException("Too many AX.25 addresses");
            if (pos + 7 > frame.length)
                throw new IllegalArgumentException("Truncated AX.25 address");

            StringBuilder call = new StringBuilder(6);
            for (int i = 0; i < 6; i++) {
                int encoded = frame[pos + i] & 0xff;
                int ch = (encoded >>> 1) & 0x7f;
                if (ch < 0x20 || ch > 0x7e)
                    throw new IllegalArgumentException("Invalid AX.25 callsign character");
                call.append((char) ch);
            }

            String base = call.toString().trim();
            if (base.length() == 0)
                throw new IllegalArgumentException("Empty AX.25 callsign");

            int ssidOctet = frame[pos + 6] & 0xff;
            int ssid = (ssidOctet >>> 1) & 0x0f;
            last = (ssidOctet & 0x01) != 0;
            boolean repeated = (ssidOctet & 0x80) != 0;

            String rendered = ssid == 0 ? base : base + "-" + ssid;
            addresses.add(new Address(rendered, repeated));
            pos += 7;
        }

        if (addresses.size() < 2)
            throw new IllegalArgumentException("AX.25 needs destination and source");
        if (pos + 2 > frame.length)
            throw new IllegalArgumentException("Missing AX.25 control/PID");

        int control = frame[pos++] & 0xff;
        int pid = frame[pos++] & 0xff;
        if (control != 0x03 || pid != 0xF0)
            throw new IllegalArgumentException("Not AX.25 UI/no-layer3 frame");

        StringBuilder out = new StringBuilder();
        out.append(addresses.get(1).call);
        out.append('>');
        out.append(addresses.get(0).call);

        for (int i = 2; i < addresses.size(); i++) {
            Address digi = addresses.get(i);
            out.append(',').append(digi.call);
            if (digi.repeated)
                out.append('*');
        }

        out.append(':');
        out.append(new String(frame, pos, frame.length - pos, LATIN1));
        return out.toString();
    }
}
