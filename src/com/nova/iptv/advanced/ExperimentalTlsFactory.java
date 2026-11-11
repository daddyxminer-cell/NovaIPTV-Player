package com.nova.iptv.advanced;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.conscrypt.Conscrypt;

/** Per-policy TLS factory: original trust checks remain in Conscrypt and OkHttp. */
public final class ExperimentalTlsFactory extends SSLSocketFactory {
    private final SSLSocketFactory engineFactory;
    private final boolean fragment, omitSni, continuous;

    public ExperimentalTlsFactory(SSLSocketFactory engineFactory, boolean fragment, boolean omitSni) {
        this(engineFactory, fragment, omitSni, false);
    }
    public ExperimentalTlsFactory(SSLSocketFactory engineFactory, boolean fragment, boolean omitSni, boolean continuous) {
        this.engineFactory = unwrapEngineFactory(engineFactory);
        this.continuous = continuous;
        this.fragment = fragment;
        this.omitSni = omitSni;
        Conscrypt.setUseEngineSocket(this.engineFactory, true);
    }
    private static SSLSocketFactory unwrapEngineFactory(SSLSocketFactory factory) {
        // Android 5.0 wraps the bundled provider in its own KitKat adapter. Reflect
        // only on our pinned app-owned library, never hidden Android platform APIs.
        if (Conscrypt.isConscrypt(factory)) return factory;
        Class<?> type = factory.getClass();
        while (type != null && type.getName().startsWith("org.conscrypt.")) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField("delegate");
                field.setAccessible(true);
                Object delegate = field.get(factory);
                if (delegate instanceof SSLSocketFactory && Conscrypt.isConscrypt((SSLSocketFactory) delegate)) {
                    return (SSLSocketFactory) delegate;
                }
            } catch (NoSuchFieldException e) {
                // The adapter superclass owns the delegate.
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Cannot access app-owned TLS engine", e);
            }
            type = type.getSuperclass();
        }
        throw new IllegalStateException("Experimental TLS requires the bundled engine provider");
    }
    @Override public String[] getDefaultCipherSuites() { return engineFactory.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return engineFactory.getSupportedCipherSuites(); }

    @Override public Socket createSocket(Socket raw, String host, int port, boolean autoClose) throws IOException {
        Socket transport = continuous ? ContinuousSocket.wrap(raw) : raw;
        if (fragment) {
            raw.setTcpNoDelay(true);
            final OutputStream output = new ClientHelloOutputStream(transport.getOutputStream());
            transport = new ForwardingSocket(transport) {
                @Override public OutputStream getOutputStream() { return output; }
            };
        }
        SSLSocket tls = (SSLSocket) engineFactory.createSocket(transport, host, port, autoClose);
        // Only this app-owned TLS factory is changed; never relax chain or hostname checks.
        Conscrypt.setHostname(tls, omitSni ? null : host);
        Conscrypt.setUseSessionTickets(tls, false);
        return new VerifiedTlsSocket(tls);
    }
    @Override public Socket createSocket(String host, int port) throws IOException {
        return connect(host, port, null, 0);
    }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
        return connect(host, port, local, localPort);
    }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException {
        return connect(host.getHostAddress(), port, null, 0);
    }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
        return connect(host.getHostAddress(), port, local, localPort);
    }
    private Socket connect(String host, int port, InetAddress local, int localPort) throws IOException {
        Socket raw = new Socket();
        try {
            if (local != null) raw.bind(new InetSocketAddress(local, localPort));
            raw.connect(new InetSocketAddress(host, port), 8000);
            return createSocket(raw, host, port, true);
        } catch (IOException | RuntimeException e) {
            try { raw.close(); } catch (IOException ignored) { }
            throw e;
        }
    }
    @Override public Socket createSocket() throws IOException {
        // OkHttp always uses the connected, layered overload; fail rather than lose policy.
        throw new IOException("Experimental TLS requires a connected transport socket");
    }
}
