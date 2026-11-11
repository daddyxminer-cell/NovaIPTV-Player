package com.nova.iptv.advanced;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;

/** Bounded first-record buffering and byte-preserving SNI write splitting; never edits TLS. */
public final class ClientHelloOutputStream extends OutputStream {
    private static final int MAX_RECORD = 16384;
    private final OutputStream delegate;
    private final byte[] record = new byte[5 + MAX_RECORD];
    private int count;
    private int expected = 5;
    private boolean done;

    public ClientHelloOutputStream(OutputStream delegate) { this.delegate = delegate; }

    @Override public synchronized void write(int value) throws IOException {
        write(new byte[]{(byte)value}, 0, 1);
    }
    @Override public synchronized void write(byte[] bytes, int offset, int length) throws IOException {
        if (bytes == null) throw new NullPointerException("bytes");
        if (offset < 0 || length < 0 || offset > bytes.length - length) throw new IndexOutOfBoundsException();
        while (length > 0) {
            if (done) { delegate.write(bytes, offset, length); return; }
            int n = Math.min(length, expected - count);
            System.arraycopy(bytes, offset, record, count, n);
            count += n; offset += n; length -= n;
            if (count == 5 && expected == 5) {
                int bodyLength = u16(record, 3);
                if ((record[0] & 255) != 22 || (record[1] & 255) != 3
                        || bodyLength < 4 || bodyLength > MAX_RECORD) {
                    emit(-1);
                    continue;
                }
                expected = 5 + bodyLength;
            }
            if (count == expected) emit(sniSplitOffset(record, count));
        }
    }

    /** Returns a split strictly inside an intact first-record SNI hostname, or -1. */
    static int sniSplitOffset(byte[] b, int length) {
        if (length < 9 || length > b.length || (b[0] & 255) != 22 || (b[5] & 255) != 1
                || u16(b, 3) + 5 != length) return -1;
        int helloLength = ((b[6] & 255) << 16) | ((b[7] & 255) << 8) | (b[8] & 255);
        int end = 9 + helloLength;
        if (end > length || end < 44) return -1;
        int p = 43; // Handshake header, legacy version and 32-byte random.
        int sid = b[p++] & 255;
        if (sid > 32 || p + sid + 2 > end) return -1;
        p += sid;
        int ciphers = u16(b, p); p += 2;
        if (ciphers < 2 || (ciphers & 1) != 0 || p + ciphers + 1 > end) return -1;
        p += ciphers;
        int compression = b[p++] & 255;
        if (compression == 0 || p + compression + 2 > end) return -1;
        p += compression;
        int extensions = u16(b, p); p += 2;
        if (p + extensions != end) return -1;
        while (p + 4 <= end) {
            int type = u16(b, p), size = u16(b, p + 2); p += 4;
            int next = p + size;
            if (next > end) return -1;
            if (type == 0) {
                if (size < 2 || p + 2 + u16(b, p) != next) return -1;
                p += 2;
                int target = -1;
                while (p + 3 <= next) {
                    int nameType = b[p++] & 255;
                    int nameLength = u16(b, p); p += 2;
                    if (p + nameLength > next) return -1;
                    if (nameType == 0 && nameLength >= 2 && target == -1) target = p + nameLength / 2;
                    p += nameLength;
                }
                return p == next ? target : -1;
            }
            p = next;
        }
        return -1;
    }
    private static int u16(byte[] bytes, int offset) {
        return ((bytes[offset] & 255) << 8) | (bytes[offset + 1] & 255);
    }
    private void emit(int split) throws IOException {
        done = true; // No retries or duplicate writes after a transport failure.
        int sent = 0;
        if (count >= 2) {
            delegate.write(record, 0, 2); sent = 2;
            delegate.flush(); pause();
        }
        if (count >= 5) {
            delegate.write(record, 2, 3); sent = 5;
            delegate.flush(); pause();
        }
        if (split > sent && split < count) {
            delegate.write(record, sent, split - sent); sent = split;
            delegate.flush(); pause();
        }
        if (count > sent) delegate.write(record, sent, count - sent);
        count = 0;
    }
    private static void pause() throws InterruptedIOException {
        try { Thread.sleep(1); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted during experimental ClientHello writes");
        }
    }
    @Override public synchronized void flush() throws IOException {
        // Never wait for more bytes after the TLS engine asks to flush a partial record.
        if (!done && count > 0) emit(-1);
        delegate.flush();
    }
    @Override public synchronized void close() throws IOException {
        try { if (!done && count > 0) emit(-1); }
        finally { done = true; delegate.close(); }
    }
}
