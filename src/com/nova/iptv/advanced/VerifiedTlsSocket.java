package com.nova.iptv.advanced;

/** Opaque wrapper prevents OkHttp platform adapters from re-enabling SNI. */
final class VerifiedTlsSocket extends javax.net.ssl.SSLSocket {
    private final javax.net.ssl.SSLSocket delegate;
    VerifiedTlsSocket(javax.net.ssl.SSLSocket delegate) { this.delegate = delegate; }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
    @Override public String[] getEnabledCipherSuites() { return delegate.getEnabledCipherSuites(); }
    @Override public void setEnabledCipherSuites(String[] values) { delegate.setEnabledCipherSuites(values); }
    @Override public String[] getSupportedProtocols() { return delegate.getSupportedProtocols(); }
    @Override public String[] getEnabledProtocols() { return delegate.getEnabledProtocols(); }
    @Override public void setEnabledProtocols(String[] values) { delegate.setEnabledProtocols(values); }
    @Override public javax.net.ssl.SSLSession getSession() { return delegate.getSession(); }
    @Override public void addHandshakeCompletedListener(javax.net.ssl.HandshakeCompletedListener listener) { delegate.addHandshakeCompletedListener(listener); }
    @Override public void removeHandshakeCompletedListener(javax.net.ssl.HandshakeCompletedListener listener) { delegate.removeHandshakeCompletedListener(listener); }
    @Override public void startHandshake() throws java.io.IOException { delegate.startHandshake(); }
    @Override public void setUseClientMode(boolean value) { delegate.setUseClientMode(value); }
    @Override public boolean getUseClientMode() { return delegate.getUseClientMode(); }
    @Override public void setNeedClientAuth(boolean value) { delegate.setNeedClientAuth(value); }
    @Override public boolean getNeedClientAuth() { return delegate.getNeedClientAuth(); }
    @Override public void setWantClientAuth(boolean value) { delegate.setWantClientAuth(value); }
    @Override public boolean getWantClientAuth() { return delegate.getWantClientAuth(); }
    @Override public void setEnableSessionCreation(boolean value) { delegate.setEnableSessionCreation(value); }
    @Override public boolean getEnableSessionCreation() { return delegate.getEnableSessionCreation(); }
    @Override public javax.net.ssl.SSLParameters getSSLParameters() { return delegate.getSSLParameters(); }
    @Override public void setSSLParameters(javax.net.ssl.SSLParameters value) { delegate.setSSLParameters(value); }

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
