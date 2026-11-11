package com.nova.iptv.advanced;

import java.io.IOException;
import java.security.SecureRandom;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/** Random HTTP request-header length only: this is not RFC 8446 TLS-record padding. */
public final class HttpPaddingInterceptor implements Interceptor {
    static final int MIN_PADDING = 32;
    static final int MAX_PADDING = 256;
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();
    private final NetworkPolicy policy;
    private final SecureRandom random = new SecureRandom();
    public HttpPaddingInterceptor(NetworkPolicy policy) { this.policy = policy; }

    Request apply(Request original) {
        if (!policy.current().httpPaddingEnabled) return original;
        int length = MIN_PADDING + random.nextInt(MAX_PADDING - MIN_PADDING + 1);
        char[] padding = new char[length];
        for (int i = 0; i < length; i++) padding[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        // Replace, rather than append, so redirects/retries cannot accumulate padding.
        return original.newBuilder().header("X-Padding", new String(padding)).build();
    }
    @Override public Response intercept(Chain chain) throws IOException {
        return chain.proceed(apply(chain.request()));
    }
}
