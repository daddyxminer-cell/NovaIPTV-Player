package com.nova.iptv.advanced;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.ConnectionPool;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.dnsoverhttps.DnsOverHttps;

/** Request-time settings shared by playback, playlists and the Xtream API. */
public final class NetworkPolicy {
    public static final class Config {
        public final boolean userAgentEnabled, refererEnabled;
        public final boolean fragmentationEnabled, omitSniEnabled;
        public final boolean continuousFragmentationEnabled, httpPaddingEnabled, hostCaseEnabled;
        public final String userAgent, referer;
        public final int dnsMode;

        public Config(boolean uaEnabled, String ua, boolean refEnabled, String ref, int dns) {
            this(uaEnabled, ua, refEnabled, ref, dns, false, false);
        }

        public Config(boolean uaEnabled, String ua, boolean refEnabled, String ref, int dns,
                      boolean fragmentation, boolean omitSni) {
            this(uaEnabled, ua, refEnabled, ref, dns, fragmentation, omitSni, false, false);
        }

        public Config(boolean uaEnabled, String ua, boolean refEnabled, String ref, int dns,
                      boolean fragmentation, boolean omitSni, boolean continuous, boolean httpPadding) {
            this(uaEnabled, ua, refEnabled, ref, dns, fragmentation, omitSni, continuous, httpPadding, false);
        }

        public Config(boolean uaEnabled, String ua, boolean refEnabled, String ref, int dns,
                      boolean fragmentation, boolean omitSni, boolean continuous, boolean httpPadding, boolean hostCase) {
            hostCaseEnabled = hostCase;
            continuousFragmentationEnabled = continuous;
            httpPaddingEnabled = httpPadding;
            fragmentationEnabled = fragmentation;
            omitSniEnabled = omitSni;
            userAgentEnabled = uaEnabled;
            refererEnabled = refEnabled;
            userAgent = ua == null ? "" : ua.trim();
            referer = ref == null ? "" : ref.trim();
            dnsMode = dns >= 0 && dns <= 2 ? dns : 0;
        }
    }

    private volatile Config config = new Config(false, "", false, "", 0);
    private final List<WeakReference<ConnectionPool>> pools = new ArrayList<>();
    private volatile Dns cloudflare, google;
    private final Dns dynamicDns = new Dns() {
        @Override public List<InetAddress> lookup(String host) throws UnknownHostException {
            int mode = config.dnsMode;
            // Explicit custom modes never silently fall back to ISP/system DNS.
            return mode == 0 ? Dns.SYSTEM.lookup(host) : resolver(mode).lookup(host);
        }
    };
    private final Interceptor headers = new Interceptor() {
        @Override public Response intercept(Chain chain) throws IOException {
            return chain.proceed(applyHeaders(chain.request()));
        }
    };

    public Config current() { return config; }

    public synchronized void update(Config next) {
        boolean dnsChanged = next.dnsMode != config.dnsMode
            || next.fragmentationEnabled != config.fragmentationEnabled
            || next.omitSniEnabled != config.omitSniEnabled
            || next.continuousFragmentationEnabled != config.continuousFragmentationEnabled
            || next.httpPaddingEnabled != config.httpPaddingEnabled
            || next.hostCaseEnabled != config.hostCaseEnabled;
        config = next;
        if (dnsChanged) {
            // Force new connections to consult the new resolver; don't cancel active streams.
            Iterator<WeakReference<ConnectionPool>> it = pools.iterator();
            while (it.hasNext()) {
                ConnectionPool pool = it.next().get();
                if (pool == null) it.remove(); else pool.evictAll();
            }
        }
    }

    public synchronized OkHttpClient.Builder configure(OkHttpClient.Builder builder) {
        ConnectionPool pool = new ConnectionPool();
        trackPool(pool);
        return builder.connectionPool(pool).dns(dynamicDns).addNetworkInterceptor(headers)
            .addNetworkInterceptor(new HttpPaddingInterceptor(this))
            .addNetworkInterceptor(new HostCaseInterceptor(this));
    }

    synchronized void trackPool(ConnectionPool pool) {
        Iterator<WeakReference<ConnectionPool>> it = pools.iterator();
        while (it.hasNext()) if (it.next().get() == null) it.remove();
        pools.add(new WeakReference<>(pool));
    }

    public Request applyHeaders(Request original) {
        Config c = config;
        Request.Builder result = original.newBuilder();
        if (c.userAgentEnabled && validHeader(c.userAgent, 1024)) {
            result.header("User-Agent", c.userAgent);
        }
        if (c.refererEnabled && validReferer(c.referer)) {
            result.header("Referer", c.referer);
        }
        return result.build();
    }

    public static boolean validHeader(String value, int max) {
        if (value == null || value.trim().isEmpty() || value.length() > max) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch < 0x20 || ch > 0x7e) return false;
        }
        return true;
    }

    public static boolean validReferer(String value) {
        if (!validHeader(value, 2048)) return false;
        if (!(value.startsWith("https://") || value.startsWith("http://"))) return false;
        try {
            HttpUrl parsed = HttpUrl.get(value);
            return parsed.username().isEmpty() && parsed.password().isEmpty()
                && parsed.fragment() == null && value.indexOf(' ') < 0;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public Dns dns() { return dynamicDns; }

    private synchronized Dns resolver(int mode) throws UnknownHostException {
        Dns existing = mode == 1 ? cloudflare : google;
        if (existing != null) return existing;
        boolean cf = mode == 1;
        // getByAddress parses bytes only: no DNS lookup is needed to reach DoH.
        InetAddress primary = InetAddress.getByAddress(cf
            ? new byte[]{1, 1, 1, 1} : new byte[]{8, 8, 8, 8});
        InetAddress secondary = InetAddress.getByAddress(cf
            ? new byte[]{1, 0, 0, 1} : new byte[]{8, 8, 4, 4});
        OkHttpClient transport = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS).build();
        Dns dns = new DnsOverHttps.Builder().client(transport)
            .url(HttpUrl.get(cf ? "https://cloudflare-dns.com/dns-query"
                               : "https://dns.google/dns-query"))
            .bootstrapDnsHosts(primary, secondary).includeIPv6(true)
            .resolvePrivateAddresses(true).build();
        if (cf) cloudflare = dns; else google = dns;
        return dns;
    }
}
