package com.nova.iptv.advanced;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import javax.net.SocketFactory;

/** Covers cleartext HTTP as well as the raw socket beneath HTTPS. No VPN or proxy is created. */
public final class ContinuousSocketFactory extends SocketFactory {
    private final SocketFactory delegate;
    public ContinuousSocketFactory(SocketFactory delegate) { this.delegate = delegate; }
    @Override public Socket createSocket() throws IOException { return ContinuousSocket.wrap(delegate.createSocket()); }
    @Override public Socket createSocket(String host, int port) throws IOException {
        return ContinuousSocket.wrap(delegate.createSocket(host, port));
    }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
        return ContinuousSocket.wrap(delegate.createSocket(host, port, local, localPort));
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return ContinuousSocket.wrap(delegate.createSocket(host, port));
    }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
        return ContinuousSocket.wrap(delegate.createSocket(host, port, local, localPort));
    }
}
