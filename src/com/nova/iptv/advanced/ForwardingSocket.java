package com.nova.iptv.advanced;

/** Preserves the connected transport and all Socket options for engine-based TLS. */
class ForwardingSocket extends java.net.Socket {
    protected final java.net.Socket delegate;
    ForwardingSocket(java.net.Socket delegate) { this.delegate = delegate; }

    @Override public void connect(java.net.SocketAddress address) throws java.io.IOException { delegate.connect(address); }
    @Override public void connect(java.net.SocketAddress address, int timeout) throws java.io.IOException { delegate.connect(address, timeout); }
    @Override public void bind(java.net.SocketAddress address) throws java.io.IOException { delegate.bind(address); }
    @Override public java.net.InetAddress getInetAddress() { return delegate.getInetAddress(); }
    @Override public java.net.InetAddress getLocalAddress() { return delegate.getLocalAddress(); }
    @Override public int getPort() { return delegate.getPort(); }
    @Override public int getLocalPort() { return delegate.getLocalPort(); }
    @Override public java.net.SocketAddress getRemoteSocketAddress() { return delegate.getRemoteSocketAddress(); }
    @Override public java.net.SocketAddress getLocalSocketAddress() { return delegate.getLocalSocketAddress(); }
    @Override public java.nio.channels.SocketChannel getChannel() { return delegate.getChannel(); }
    @Override public java.io.InputStream getInputStream() throws java.io.IOException { return delegate.getInputStream(); }
    @Override public java.io.OutputStream getOutputStream() throws java.io.IOException { return delegate.getOutputStream(); }
    @Override public void setTcpNoDelay(boolean value) throws java.net.SocketException { delegate.setTcpNoDelay(value); }
    @Override public boolean getTcpNoDelay() throws java.net.SocketException { return delegate.getTcpNoDelay(); }
    @Override public void setSoLinger(boolean enabled, int value) throws java.net.SocketException { delegate.setSoLinger(enabled, value); }
    @Override public int getSoLinger() throws java.net.SocketException { return delegate.getSoLinger(); }
    @Override public void sendUrgentData(int value) throws java.io.IOException { delegate.sendUrgentData(value); }
    @Override public void setOOBInline(boolean value) throws java.net.SocketException { delegate.setOOBInline(value); }
    @Override public boolean getOOBInline() throws java.net.SocketException { return delegate.getOOBInline(); }
    @Override public void setSoTimeout(int value) throws java.net.SocketException { delegate.setSoTimeout(value); }
    @Override public int getSoTimeout() throws java.net.SocketException { return delegate.getSoTimeout(); }
    @Override public void setSendBufferSize(int value) throws java.net.SocketException { delegate.setSendBufferSize(value); }
    @Override public int getSendBufferSize() throws java.net.SocketException { return delegate.getSendBufferSize(); }
    @Override public void setReceiveBufferSize(int value) throws java.net.SocketException { delegate.setReceiveBufferSize(value); }
    @Override public int getReceiveBufferSize() throws java.net.SocketException { return delegate.getReceiveBufferSize(); }
    @Override public void setKeepAlive(boolean value) throws java.net.SocketException { delegate.setKeepAlive(value); }
    @Override public boolean getKeepAlive() throws java.net.SocketException { return delegate.getKeepAlive(); }
    @Override public void setTrafficClass(int value) throws java.net.SocketException { delegate.setTrafficClass(value); }
    @Override public int getTrafficClass() throws java.net.SocketException { return delegate.getTrafficClass(); }
    @Override public void setReuseAddress(boolean value) throws java.net.SocketException { delegate.setReuseAddress(value); }
    @Override public boolean getReuseAddress() throws java.net.SocketException { return delegate.getReuseAddress(); }
    @Override public void close() throws java.io.IOException { delegate.close(); }
    @Override public void shutdownInput() throws java.io.IOException { delegate.shutdownInput(); }
    @Override public void shutdownOutput() throws java.io.IOException { delegate.shutdownOutput(); }
    @Override public boolean isConnected() { return delegate.isConnected(); }
    @Override public boolean isBound() { return delegate.isBound(); }
    @Override public boolean isClosed() { return delegate.isClosed(); }
    @Override public boolean isInputShutdown() { return delegate.isInputShutdown(); }
    @Override public boolean isOutputShutdown() { return delegate.isOutputShutdown(); }
    @Override public void setPerformancePreferences(int a, int b, int c) { delegate.setPerformancePreferences(a, b, c); }
    @Override public String toString() { return delegate.toString(); }
}
