package org.aprsdroid.app;

import java.io.ByteArrayOutputStream;

/**
 * Lightweight KISS stream guard used by BLE transports.
 *
 * It never decodes or rewrites valid KISS payload bytes. It only keeps enough
 * framing state to bound a malformed/incomplete frame and to emit debug frame
 * metadata. KISS escaping/unescaping remains owned by KissProto.
 */
public final class BleKissStreamGuard {
    public static final int FEND = 0xC0;
    public static final int DEFAULT_MAX_FRAME_BYTES = 2048;

    public interface Listener {
        void onFrame(int length, int port, int command);
        void onReset(String reason);
    }

    private final int maxFrameBytes;
    private final Listener listener;

    private boolean inFrame;
    private boolean droppingOversize;
    private int frameLength;
    private int commandByte = -1;

    public BleKissStreamGuard(Listener listener) {
        this(DEFAULT_MAX_FRAME_BYTES, listener);
    }

    public BleKissStreamGuard(int maxFrameBytes, Listener listener) {
        if (maxFrameBytes < 1)
            throw new IllegalArgumentException("maxFrameBytes must be positive");
        this.maxFrameBytes = maxFrameBytes;
        this.listener = listener;
    }

    /**
     * Filter one arbitrary BLE notification/chunk.
     *
     * For a valid stream, returned bytes are byte-for-byte identical to input.
     * If a frame exceeds maxFrameBytes, excess bytes are discarded until the
     * next FEND so KissProto cannot accumulate an unbounded partial frame.
     */
    public synchronized byte[] filter(byte[] input) {
        if (input == null || input.length == 0)
            return new byte[0];

        ByteArrayOutputStream out = new ByteArrayOutputStream(input.length);

        for (byte value : input) {
            int b = value & 0xFF;

            if (b == FEND) {
                if (droppingOversize) {
                    // Resynchronise on the first delimiter after the dropped
                    // oversized tail. Forward the delimiter so KissProto also
                    // reaches a clean frame boundary.
                    droppingOversize = false;
                    inFrame = true;
                    frameLength = 0;
                    commandByte = -1;
                    out.write(b);
                    continue;
                }

                if (inFrame && frameLength > 0 && commandByte >= 0 && listener != null) {
                    listener.onFrame(
                            frameLength,
                            (commandByte >>> 4) & 0x0F,
                            commandByte & 0x0F);
                }

                // A KISS FEND can close one frame and open the next.
                inFrame = true;
                frameLength = 0;
                commandByte = -1;
                out.write(b);
                continue;
            }

            if (!inFrame) {
                // Preserve any pre-frame bytes. KissProto owns protocol parsing.
                out.write(b);
                continue;
            }

            frameLength++;
            if (commandByte < 0)
                commandByte = b;

            if (frameLength > maxFrameBytes) {
                droppingOversize = true;
                if (listener != null)
                    listener.onReset("frame_too_large");
                // Do not forward the byte that exceeded the bound, nor any
                // later bytes until the next FEND.
                continue;
            }

            if (!droppingOversize)
                out.write(b);
        }

        return out.toByteArray();
    }

    /** Reset partial-frame state after disconnect/reconnect. */
    public synchronized void reset() {
        inFrame = false;
        droppingOversize = false;
        frameLength = 0;
        commandByte = -1;
    }
}
