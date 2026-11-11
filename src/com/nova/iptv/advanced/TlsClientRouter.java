package com.nova.iptv.advanced;

import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.conscrypt.Conscrypt;

/** Selects immutable transport variants before Call creation (keeps cancellation/timeouts intact). */
final class TlsClientRouter {
    // Values do not reference the base client; unused repositories remain collectible.
    private final Map<OkHttpClient, OkHttpClient[]> variants = new WeakHashMap<>();
    private final NetworkPolicy policy;
    TlsClientRouter(NetworkPolicy policy) { this.policy = policy; }

    synchronized OkHttpClient select(OkHttpClient original) {
        NetworkPolicy.Config c = policy.current();
        int mode = (c.fragmentationEnabled ? 1 : 0) | (c.omitSniEnabled ? 2 : 0)
            | (c.continuousFragmentationEnabled ? 4 : 0);
        if (mode == 0) return original;
        OkHttpClient[] clients = variants.get(original);
        if (clients == null) { clients = new OkHttpClient[8]; variants.put(original, clients); }
        if (clients[mode] == null) {
            try {
                X509TrustManager trust = original.x509TrustManager();
                if (trust == null) throw new GeneralSecurityException("No platform trust manager");
                SSLContext context = SSLContext.getInstance("TLS", Conscrypt.newProvider());
                context.init(null, new TrustManager[]{trust}, null);
                ConnectionPool pool = new ConnectionPool();
                policy.trackPool(pool);
                OkHttpClient.Builder builder = original.newBuilder().connectionPool(pool);
                if (c.continuousFragmentationEnabled) {
                    builder.socketFactory(new ContinuousSocketFactory(original.socketFactory()));
                }
                clients[mode] = builder.sslSocketFactory(new ExperimentalTlsFactory(context.getSocketFactory(),
                        c.fragmentationEnabled, c.omitSniEnabled, c.continuousFragmentationEnabled), trust)
                    // Opaque sockets deliberately avoid platform ALPN/SNI mutation.
                    .protocols(Collections.singletonList(Protocol.HTTP_1_1)).build();
            } catch (GeneralSecurityException | LinkageError e) {
                // Never silently send SNI or unfragmented traffic when an option was requested.
                throw new IllegalStateException("Experimental TLS unavailable; disable its switches", e);
            }
        }
        return clients[mode];
    }
}
