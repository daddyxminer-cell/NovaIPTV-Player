package com.nova.iptv.advanced;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.SocketException;
import java.util.Random;
import java.util.concurrent.locks.LockSupport;

/** Best-effort outbound write shaping, not TCP packet control or inbound-video obfuscation. */
public final class ContinuousOutputStream extends OutputStream {
    static final int MIN_FRAGMENT = 20;
    static final int MAX_FRAGMENT = 100;
    static final long MAX_DELAY_PER_WRITE_NANOS = 2_000_000L;
    private final OutputStream delegate;
    private final Random random;
    private volatile boolean closed;

    public ContinuousOutputStream(OutputStream delegate) { this(delegate, new Random()); }
    ContinuousOutputStream(OutputStream delegate, Random random) {
        this.delegate = delegate;
        this.random = random;
    }
    private void checkOpen(int transferred) throws IOException {
        if (closed) throw new SocketException("Outbound stream closed");
        if (Thread.currentThread().isInterrupted()) {
            InterruptedIOException failure = new InterruptedIOException("Outbound fragmentation interrupted");
            failure.bytesTransferred = transferred;
            throw failure;
        }
    }
    @Override public void write(int value) throws IOException { write(new byte[]{(byte)value}, 0, 1); }
    @Override public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        checkOpen(0);
        int written = 0;
        long delayDeadline = System.nanoTime() + MAX_DELAY_PER_WRITE_NANOS;
        while (written < length) {
            checkOpen(written);
            int size = Math.min(length - written, MIN_FRAGMENT + random.nextInt(MAX_FRAGMENT - MIN_FRAGMENT + 1));
            delegate.write(bytes, offset + written, size);
            delegate.flush();
            written += size;
            if (written < length) {
                long remainingBudget = delayDeadline - System.nanoTime();
                if (remainingBudget > 0) {
                    // Requested pauses are 20-100 us; OS scheduling may overshoot.
                    long requested = (20L + random.nextInt(81)) * 1000L;
                    LockSupport.parkNanos(Math.min(requested, remainingBudget));
                }
            }
        }
        checkOpen(written);
    }
    @Override public synchronized void flush() throws IOException { checkOpen(0); delegate.flush(); }
    @Override public void close() throws IOException {
        // Do not wait for the write lock: closing the underlying socket must unblock writes.
        closed = true;
        delegate.close();
    }
}
