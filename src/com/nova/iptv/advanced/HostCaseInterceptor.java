package com.nova.iptv.advanced;

import java.io.IOException;
import okhttp3.Connection;
import okhttp3.Interceptor;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;

/** Optional HTTP/1.1 field-name casing only; no destination or Host value spoofing. */
public final class HostCaseInterceptor implements Interceptor {
    private final NetworkPolicy policy;
    public HostCaseInterceptor(NetworkPolicy policy) { this.policy = policy; }
    Request apply(Request request, Protocol protocol) {
        if (!policy.current().hostCaseEnabled || protocol != Protocol.HTTP_1_1) return request;
        if (request.headers("Host").size() != 1) return request;
        String host = request.header("Host");
        if (host == null) return request; // OkHttp's bridge normally adds it before network interceptors.
        return request.newBuilder().removeHeader("Host").header("hOsT", host).build();
    }
    @Override public Response intercept(Chain chain) throws IOException {
        Connection connection = chain.connection();
        return chain.proceed(apply(chain.request(), connection == null ? null : connection.protocol()));
    }
}
