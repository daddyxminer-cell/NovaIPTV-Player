package com.nova.iptv.advanced;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Dns;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okhttp3.dnsoverhttps.DnsOverHttps;

public final class NetworkPolicyTest {
    private static int assertions;
    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
    private static NetworkPolicy.Config config(boolean ua, boolean ref, int dns) {
        return new NetworkPolicy.Config(ua, "NovaTest/1.0", ref, "https://google.com", dns);
    }
    public static void main(String[] args) throws Exception {
        NetworkPolicy policy = new NetworkPolicy();
        Request original = new Request.Builder().url("https://example.com/stream.m3u8")
            .header("User-Agent", "OriginalPlayer/7").header("Referer", "https://provider.example/")
            .header("Range", "bytes=4096-").header("Authorization", "Bearer test").build();
        check(!policy.current().userAgentEnabled && !policy.current().refererEnabled
            && policy.current().dnsMode == 0, "safe defaults");
        check(policy.applyHeaders(original).headers().equals(original.headers()), "defaults preserve headers");
        for (boolean ua : new boolean[]{false, true}) {
            for (boolean ref : new boolean[]{false, true}) {
                policy.update(config(ua, ref, 0));
                Request result = policy.applyHeaders(original);
                check(result.header("User-Agent").equals(ua ? "NovaTest/1.0" : "OriginalPlayer/7"), "UA toggle independent");
                check(result.header("Referer").equals(ref ? "https://google.com" : "https://provider.example/"), "Referer toggle independent");
                check(result.header("Range").equals("bytes=4096-"), "byte ranges preserved");
                check(result.header("Authorization").equals("Bearer test"), "authorization preserved");
                check(result.url().equals(original.url()) && result.method().equals(original.method()), "URL and method preserved");
            }
        }
        policy.update(config(true, true, 0));
        OkHttpClient client = policy.configure(new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)).build();
        check(client.connectTimeoutMillis() == 20000 && client.readTimeoutMillis() == 60000, "existing client timeouts preserved");
        check(client.networkInterceptors().size() == 3, "network interceptor installed");
        Interceptor.Chain chain = (Interceptor.Chain) Proxy.newProxyInstance(
            Interceptor.Chain.class.getClassLoader(), new Class<?>[]{Interceptor.Chain.class},
            (proxy, method, values) -> {
                if (method.getName().equals("request")) return original;
                if (method.getName().equals("proceed")) return new Response.Builder()
                    .request((Request) values[0]).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(ResponseBody.create(null, new byte[0])).build();
                throw new UnsupportedOperationException(method.getName());
            });
        try (Response response = client.networkInterceptors().get(0).intercept(chain)) {
            check(response.request().header("User-Agent").equals("NovaTest/1.0"), "interceptor injects at request time");
        }
        policy.update(config(false, false, 0));
        try (Response response = client.networkInterceptors().get(0).intercept(chain)) {
            check(response.request().header("User-Agent").equals("OriginalPlayer/7"), "existing client sees settings changes");
        }
        check(policy.current().userAgent.equals("NovaTest/1.0"), "disabled value retained");
        check(!NetworkPolicy.validHeader("", 1024), "empty UA rejected");
        check(!NetworkPolicy.validHeader(" \t", 1024), "blank UA rejected");
        check(!NetworkPolicy.validHeader("Agent\r\nX-Evil: 1", 1024), "CRLF rejected");
        check(!NetworkPolicy.validHeader("Agent\u0000", 1024), "NUL rejected");
        check(!NetworkPolicy.validHeader("Agent\u007f", 1024), "DEL rejected");
        check(!NetworkPolicy.validHeader("Agent\u00e9", 1024), "invalid header encoding rejected");
        check(!NetworkPolicy.validHeader(new String(new char[1025]).replace('\0', 'a'), 1024), "oversize UA rejected");
        check(NetworkPolicy.validHeader("Mozilla/5.0 (Linux; Android) Player/1", 1024), "browser UA accepted");
        check(NetworkPolicy.validReferer("https://google.com"), "HTTPS Referer accepted");
        check(NetworkPolicy.validReferer("http://example.com/path?q=abc"), "HTTP Referer accepted");
        for (String ref : new String[]{"", "example.com", "ftp://example.com", "https://", "https://a.com/a b",
                "https://user:password@example.com/", "https://example.com/#fragment", "https://a.com/\r\nX: y"}) {
            check(!NetworkPolicy.validReferer(ref), "invalid Referer rejected: " + ref);
        }
        policy.update(new NetworkPolicy.Config(true, "bad\nheader", true, "ftp://bad", 0));
        check(policy.applyHeaders(original).headers().equals(original.headers()), "corrupt stored headers fail safe");
        check(new NetworkPolicy.Config(false, null, false, null, 99).dnsMode == 0, "invalid saved DNS mode normalized");
        check(new NetworkPolicy.Config(true, "  UA  ", true, " https://google.com ", 0).userAgent.equals("UA"), "saved inputs trimmed");
        Method resolver = NetworkPolicy.class.getDeclaredMethod("resolver", int.class);
        resolver.setAccessible(true);
        for (int mode = 1; mode <= 2; mode++) {
            DnsOverHttps doh = (DnsOverHttps) resolver.invoke(policy, mode);
            check(doh.url().toString().equals(mode == 1 ? "https://cloudflare-dns.com/dns-query" : "https://dns.google/dns-query"), "correct encrypted endpoint");
            List<InetAddress> ips = doh.client().dns().lookup(doh.url().host());
            check(ips.size() == 2, "both bootstrap DNS addresses configured");
            check(ips.get(0).getHostAddress().equals(mode == 1 ? "1.1.1.1" : "8.8.8.8"), "correct primary bootstrap address");
            check(ips.get(1).getHostAddress().equals(mode == 1 ? "1.0.0.1" : "8.8.4.4"), "correct secondary bootstrap address");
            check(doh.client().networkInterceptors().isEmpty(), "no IPTV headers sent to DNS provider");
            try {
                doh.client().dns().lookup("localhost");
                throw new AssertionError("bootstrap DNS unexpectedly used system resolver");
            } catch (UnknownHostException expected) { assertions++; }
        }
        Field cf = NetworkPolicy.class.getDeclaredField("cloudflare");
        Field google = NetworkPolicy.class.getDeclaredField("google");
        cf.setAccessible(true); google.setAccessible(true);
        cf.set(policy, (Dns) host -> Arrays.asList(InetAddress.getByAddress(new byte[]{1,2,3,4})));
        google.set(policy, (Dns) host -> Arrays.asList(InetAddress.getByAddress(new byte[]{4,3,2,1})));
        policy.update(config(false, false, 1));
        check(client.dns().lookup("test.invalid").get(0).getHostAddress().equals("1.2.3.4"), "existing client switches to Cloudflare");
        policy.update(config(false, false, 2));
        check(client.dns().lookup("test.invalid").get(0).getHostAddress().equals("4.3.2.1"), "existing client switches to Google");
        google.set(policy, (Dns) host -> { throw new UnknownHostException("simulated resolver outage"); });
        try {
            client.dns().lookup("localhost");
            throw new AssertionError("custom DNS fell back to system resolver");
        } catch (UnknownHostException expected) { assertions++; }
        policy.update(config(false, false, 0));
        check(!client.dns().lookup("localhost").isEmpty(), "System Default restored");
        System.out.println("PASS: " + assertions + " networking assertions (no external service needed)");
        if (args.length > 0 && args[0].equals("--live")) {
            NetworkPolicy live = new NetworkPolicy();
            for (int mode = 1; mode <= 2; mode++) {
                live.update(config(false, false, mode));
                List<InetAddress> answers = live.dns().lookup("example.com");
                check(!answers.isEmpty(), "live DoH answer");
                System.out.println("PASS: live " + (mode == 1 ? "Cloudflare" : "Google") + " DoH: " + answers);
            }
        }
    }
}
