package dev.seleniummcp;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;

/** Enforce a byte limit before the SDK's newline-delimited JSON reader allocates a whole string. */
final class ProtocolInput extends FilterInputStream {
    private final CountDownLatch disconnected;
    private final int limit;
    private int length;

    ProtocolInput(InputStream input, CountDownLatch disconnected, int limit) {
        super(input);
        this.disconnected = disconnected;
        this.limit = limit;
    }

    @Override public int read(byte[] bytes, int offset, int size) throws IOException {
        try {
            int count = super.read(bytes, offset, size);
            if (count < 0) disconnected.countDown();
            for (int i = 0; i < count; i++) accept(bytes[offset + i]);
            return count;
        } catch (IOException e) {
            disconnected.countDown();
            throw e;
        }
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : Byte.toUnsignedInt(one[0]);
    }

    private void accept(byte value) throws IOException {
        if (value == '\r' || value == '\n') length = 0;
        else if (++length > limit) throw new IOException("MCP message exceeds the configured transport limit.");
    }
}
