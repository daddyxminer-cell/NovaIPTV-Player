package com.nova.iptv.advanced;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

/** One shaping stream per connected transport; TLS wraps this beneath encryption. */
final class ContinuousSocket extends ForwardingSocket {
    private OutputStream output;
    private ContinuousSocket(Socket raw) throws IOException {
        super(raw);
        raw.setTcpNoDelay(true);
    }
    static Socket wrap(Socket raw) throws IOException {
        if (raw instanceof ContinuousSocket) return raw;
        try { return new ContinuousSocket(raw); }
        catch (IOException | RuntimeException e) {
            try { raw.close(); } catch (IOException ignored) { }
            throw e;
        }
    }
    @Override public synchronized OutputStream getOutputStream() throws IOException {
        if (output == null) output = new ContinuousOutputStream(delegate.getOutputStream());
        return output;
    }
}
