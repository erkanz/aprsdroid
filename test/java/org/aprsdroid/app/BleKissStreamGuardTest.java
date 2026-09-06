package org.aprsdroid.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

public class BleKissStreamGuardTest {
    private static final byte[] RT950_FRAME = hex(
            "C0 00 " +
            "82 A0 82 A8 70 62 E2 " +
            "96 8A 64 84 A6 88 6E " +
            "AE 92 88 8A 62 40 62 " +
            "AE 92 88 8A 64 40 63 " +
            "03 F0 " +
            "21 33 34 31 32 2E 37 33 4E 2F 31 30 38 34 39 2E " +
            "3A 30 45 26 30 30 30 2F 30 30 30 2F " +
            "41 3D 30 30 30 30 30 30 " +
            "41 50 52 53 43 4E 20 57 49 46 49 20 34 2E 33 30 56 " +
            "C0");

    private static final class FrameEvent {
        final int length;
        final int port;
        final int command;

        FrameEvent(int length, int port, int command) {
            this.length = length;
            this.port = port;
            this.command = command;
        }
    }

    private static final class Recorder implements BleKissStreamGuard.Listener {
        final List<FrameEvent> frames = new ArrayList<>();
        final List<String> resets = new ArrayList<>();

        @Override public void onFrame(int length, int port, int command) {
            frames.add(new FrameEvent(length, port, command));
        }

        @Override public void onReset(String reason) {
            resets.add(reason);
        }
    }

    @Test public void rt950Frame20ByteNotificationsRemainOneKissStream() throws Exception {
        assertChunks(new int[] {20, 20, 20, 20, 6});
    }

    @Test public void rt950FrameOneByteCallbacksRemainOneKissStream() throws Exception {
        int[] chunks = new int[RT950_FRAME.length];
        java.util.Arrays.fill(chunks, 1);
        assertChunks(chunks);
    }

    @Test public void rt950FrameSingleCallbackRemainsOneKissStream() throws Exception {
        assertChunks(new int[] {RT950_FRAME.length});
    }

    @Test public void rt950FrameSeparateBoundaryCallbacksRemainOneKissStream() throws Exception {
        assertChunks(new int[] {1, RT950_FRAME.length - 2, 1});
    }

    @Test public void kissEscapeBytesPassThroughWithoutTransportUnescape() throws Exception {
        byte[] escapedWire = hex("C0 00 11 DB DC 22 DB DD 33 C0");
        Recorder recorder = new Recorder();
        BleKissStreamGuard guard = new BleKissStreamGuard(recorder);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(guard.filter(java.util.Arrays.copyOfRange(escapedWire, 0, 4)));
        out.write(guard.filter(java.util.Arrays.copyOfRange(escapedWire, 4, 7)));
        out.write(guard.filter(java.util.Arrays.copyOfRange(escapedWire, 7, escapedWire.length)));

        assertArrayEquals(escapedWire, out.toByteArray());
        assertEquals(1, recorder.frames.size());
        assertEquals(0, recorder.frames.get(0).port);
        assertEquals(0, recorder.frames.get(0).command);
    }

    @Test public void oversizedFrameIsBoundedAndResynchronisesAtNextFend() throws Exception {
        Recorder recorder = new Recorder();
        BleKissStreamGuard guard = new BleKissStreamGuard(8, recorder);

        byte[] first = hex("C0 00 01 02 03 04 05 06 07 08 09 0A");
        byte[] second = hex("0B 0C C0 C0 00 55 C0");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(guard.filter(first));
        out.write(guard.filter(second));

        assertEquals(1, recorder.resets.size());
        assertEquals("frame_too_large", recorder.resets.get(0));
        assertTrue(out.size() < first.length + second.length);
        assertEquals(1, recorder.frames.size());
        assertEquals(2, recorder.frames.get(0).length); // command + 0x55
        assertEquals(0, recorder.frames.get(0).port);
        assertEquals(0, recorder.frames.get(0).command);
    }

    @Test public void resetDiscardsPartialFrameStateForReconnect() throws Exception {
        Recorder recorder = new Recorder();
        BleKissStreamGuard guard = new BleKissStreamGuard(recorder);
        guard.filter(hex("C0 00 01 02 03"));
        guard.reset();
        byte[] next = hex("C0 00 AA C0");
        assertArrayEquals(next, guard.filter(next));
        assertEquals(1, recorder.frames.size());
        assertEquals(2, recorder.frames.get(0).length);
    }

    private static void assertChunks(int[] chunkSizes) throws Exception {
        Recorder recorder = new Recorder();
        BleKissStreamGuard guard = new BleKissStreamGuard(recorder);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int offset = 0;

        for (int chunkSize : chunkSizes) {
            int end = Math.min(RT950_FRAME.length, offset + chunkSize);
            out.write(guard.filter(java.util.Arrays.copyOfRange(RT950_FRAME, offset, end)));
            offset = end;
        }

        assertEquals(RT950_FRAME.length, offset);
        assertArrayEquals(RT950_FRAME, out.toByteArray());
        assertEquals(0, recorder.resets.size());
        assertEquals(1, recorder.frames.size());
        FrameEvent frame = recorder.frames.get(0);
        assertEquals(84, frame.length);
        assertEquals(0, frame.port);
        assertEquals(0, frame.command);
    }

    private static byte[] hex(String value) {
        String compact = value.replaceAll("\\s+", "");
        if ((compact.length() & 1) != 0)
            throw new IllegalArgumentException("odd hex length");
        byte[] out = new byte[compact.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte) Integer.parseInt(compact.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
}
