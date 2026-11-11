package com.nova.iptv.advanced;

import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Exercises the Android persistence bridge with interface fakes, not a device emulator. */
public final class AndroidBridgeTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        check(AdvancedNetwork.httpFactory().getClass().getName().equals(
            "androidx.media3.datasource.DefaultHttpDataSource$Factory"), "original transport is default");
        Map<String, Object> stored = new HashMap<>();
        int[] applyCount = {0};
        SharedPreferences.Editor[] editor = new SharedPreferences.Editor[1];
        editor[0] = (SharedPreferences.Editor) Proxy.newProxyInstance(
            SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
            (proxy, method, values) -> {
                if (method.getName().startsWith("put")) {
                    stored.put((String) values[0], values[1]);
                    return editor[0];
                }
                if (method.getName().equals("apply")) { applyCount[0]++; return null; }
                throw new UnsupportedOperationException(method.getName());
            });
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(
            SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
            (proxy, method, values) -> {
                if (method.getName().equals("edit")) return editor[0];
                throw new UnsupportedOperationException(method.getName());
            });
        Field pref = AdvancedNetwork.class.getDeclaredField("preferences");
        pref.setAccessible(true);
        pref.set(null, preferences);
        AdvancedNetwork.save(new NetworkPolicy.Config(true, "MyPlayer/2", true, "https://google.com", 1));
        check(applyCount[0] == 1 && stored.size() == 10, "all ten settings saved atomically");
        check(Boolean.TRUE.equals(stored.get("ua_enabled")) && "MyPlayer/2".equals(stored.get("ua")), "UA and toggle persisted");
        check(Boolean.TRUE.equals(stored.get("referer_enabled")) && "https://google.com".equals(stored.get("referer")), "Referer and toggle persisted");
        check(Integer.valueOf(1).equals(stored.get("dns")), "DNS selection persisted");
        check(AdvancedNetwork.POLICY.current().dnsMode == 1, "save immediately updates live policy");
        check(AdvancedNetwork.httpFactory().getClass().getName().equals(
            "androidx.media3.datasource.okhttp.OkHttpDataSource$Factory"), "advanced settings use configured OkHttp transport");
        AdvancedNetwork.save(new NetworkPolicy.Config(false, "MyPlayer/2", false, "https://google.com", 0));
        check(applyCount[0] == 2 && Boolean.FALSE.equals(stored.get("ua_enabled")), "disabled toggle persisted");
        check("MyPlayer/2".equals(stored.get("ua")) && "https://google.com".equals(stored.get("referer")), "values retained when disabled");
        check(AdvancedNetwork.httpFactory().getClass().getName().equals(
            "androidx.media3.datasource.DefaultHttpDataSource$Factory"), "disabling restores original player transport");
        AdvancedNetwork.save(new NetworkPolicy.Config(false, "MyPlayer/2", false, "https://google.com", 0, true, true));
        check(Boolean.TRUE.equals(stored.get("fragmentation")) && Boolean.TRUE.equals(stored.get("omit_sni")), "experimental toggles persisted");
        check(AdvancedNetwork.POLICY.current().fragmentationEnabled && AdvancedNetwork.POLICY.current().omitSniEnabled, "experimental policy applies immediately");
        check(AdvancedNetwork.httpFactory().getClass().getName().equals("androidx.media3.datasource.okhttp.OkHttpDataSource$Factory"), "TLS-only settings activate routed playback transport");
        check(!new NetworkPolicy.Config(false,"",false,"",0).fragmentationEnabled && !new NetworkPolicy.Config(false,"",false,"",0).omitSniEnabled, "upgrade defaults keep both TLS options disabled");
        AdvancedNetwork.save(new NetworkPolicy.Config(false,"MyPlayer/2",false,"https://google.com",0,false,false,true,true));
        check(Boolean.TRUE.equals(stored.get("continuous_fragmentation")) && Boolean.TRUE.equals(stored.get("http_padding")), "v9 toggles persisted together");
        check(AdvancedNetwork.POLICY.current().continuousFragmentationEnabled && AdvancedNetwork.POLICY.current().httpPaddingEnabled, "v9 policy applies immediately");
        check(AdvancedNetwork.httpFactory().getClass().getName().equals("androidx.media3.datasource.okhttp.OkHttpDataSource$Factory"), "v9-only settings activate routed playback transport");
        check(!stored.containsKey("tls_padding"), "unimplemented TLS padding is never persisted as enabled");
        NetworkPolicy.Config migrated = new NetworkPolicy.Config(false,"",false,"",0,false,false);
        check(!migrated.continuousFragmentationEnabled && !migrated.httpPaddingEnabled, "v8 settings migrate with v9 features off");
        AdvancedNetwork.save(migrated);
        check(AdvancedNetwork.httpFactory().getClass().getName().equals("androidx.media3.datasource.DefaultHttpDataSource$Factory"), "disabling v9 options restores original player transport");
        AdvancedNetwork.save(new NetworkPolicy.Config(false,"",false,"",0,false,false,false,false,true));
        check(Boolean.TRUE.equals(stored.get("host_case")), "Host casing saved independently");
        check(AdvancedNetwork.POLICY.current().hostCaseEnabled, "Host casing policy updated immediately");
        check(AdvancedNetwork.httpFactory().getClass().getName().equals("androidx.media3.datasource.okhttp.OkHttpDataSource$Factory"), "Host-only setting activates player HTTP client");
        check(!migrated.hostCaseEnabled, "old preferences keep Host casing disabled");
        System.out.println("PASS: " + assertions + " persistence and player-transport assertions (Android interfaces faked)");
    }
}
