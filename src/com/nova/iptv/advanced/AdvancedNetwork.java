package com.nova.iptv.advanced;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.datasource.okhttp.OkHttpDataSource;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Call;
import okhttp3.Request;

/** Android persistence and the bridge into the original app's network clients. */
public final class AdvancedNetwork {
    static final NetworkPolicy POLICY = new NetworkPolicy();
    private static volatile SharedPreferences preferences;
    private static volatile OkHttpClient playerClient;
    private static final TlsClientRouter TLS = new TlsClientRouter(POLICY);

    public static synchronized void init(Context context) {
        if (preferences != null) return;
        preferences = context.getApplicationContext()
            .getSharedPreferences("nova_advanced_network", Context.MODE_PRIVATE);
        POLICY.update(new NetworkPolicy.Config(
            preferences.getBoolean("ua_enabled", false), preferences.getString("ua", ""),
            preferences.getBoolean("referer_enabled", false), preferences.getString("referer", ""),
            preferences.getInt("dns", 0), preferences.getBoolean("fragmentation", false),
            preferences.getBoolean("omit_sni", false), preferences.getBoolean("continuous_fragmentation", false),
            preferences.getBoolean("http_padding", false), preferences.getBoolean("host_case", false)));
    }

    static void save(NetworkPolicy.Config c) {
        preferences.edit().putBoolean("ua_enabled", c.userAgentEnabled).putString("ua", c.userAgent)
            .putBoolean("referer_enabled", c.refererEnabled).putString("referer", c.referer)
            .putInt("dns", c.dnsMode).putBoolean("fragmentation", c.fragmentationEnabled)
            .putBoolean("omit_sni", c.omitSniEnabled)
            .putBoolean("continuous_fragmentation", c.continuousFragmentationEnabled)
            .putBoolean("http_padding", c.httpPaddingEnabled).putBoolean("host_case", c.hostCaseEnabled).apply();
        POLICY.update(c);
    }

    public static OkHttpClient.Builder configure(OkHttpClient.Builder builder) {
        return POLICY.configure(builder);
    }

    public static Call newCall(OkHttpClient client, Request request) {
        try {
            return TLS.select(client).newCall(request);
        } catch (IllegalStateException | LinkageError e) {
            return new FailedNetworkCall(request, e);
        }
    }

    public static synchronized HttpDataSource.Factory httpFactory() {
        NetworkPolicy.Config c = POLICY.current();
        if (!c.userAgentEnabled && !c.refererEnabled && c.dnsMode == 0
                && !c.fragmentationEnabled && !c.omitSniEnabled
                && !c.continuousFragmentationEnabled && !c.httpPaddingEnabled && !c.hostCaseEnabled) {
            // Leave the original player transport completely unchanged by default.
            return new DefaultHttpDataSource.Factory();
        }
        if (playerClient == null) {
            playerClient = configure(new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
                .followSslRedirects(false)).build();
        }
        // Keep the platform User-Agent unless the user explicitly overrides it.
        return new OkHttpDataSource.Factory(new Call.Factory() {
            @Override public Call newCall(Request request) {
                return AdvancedNetwork.newCall(playerClient, request);
            }
        })
            .setUserAgent(System.getProperty("http.agent"));
    }

    private AdvancedNetwork() { }
}
