package com.sakuradata.media.util;

import java.io.IOException;
import java.io.InputStream;

public class ThrottledInputStream extends InputStream {
    private final InputStream target;
    private final long maxBytesPerSecond;
    private long bytesRead = 0;
    private long startTime = System.currentTimeMillis();

    public ThrottledInputStream(InputStream target, long maxBytesPerSecond) {
        this.target = target;
        this.maxBytesPerSecond = maxBytesPerSecond;
    }

    @Override
    public int read() throws IOException {
        throttle(1);
        return target.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int read = target.read(b, off, len);
        if (read > 0) {
            throttle(read);
        }
        return read;
    }

    private void throttle(int bytesToRead) {
        if (maxBytesPerSecond <= 0) {
            return;
        }
        bytesRead += bytesToRead;
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed == 0) {
            elapsed = 1;
        }
        long expectedTime = (bytesRead * 1000) / maxBytesPerSecond;
        if (expectedTime > elapsed) {
            try {
                Thread.sleep(expectedTime - elapsed);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public int available() throws IOException {
        return target.available();
    }

    @Override
    public synchronized void mark(int readlimit) {
        target.mark(readlimit);
    }

    @Override
    public synchronized void reset() throws IOException {
        target.reset();
    }

    @Override
    public boolean markSupported() {
        return target.markSupported();
    }

    @Override
    public long skip(long n) throws IOException {
        return target.skip(n);
    }

    @Override
    public void close() throws IOException {
        target.close();
    }
}
