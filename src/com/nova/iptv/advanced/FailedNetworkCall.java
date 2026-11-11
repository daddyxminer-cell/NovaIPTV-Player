package com.nova.iptv.advanced;

import java.io.IOException;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import okio.Timeout;

/** Reports unavailable experimental TLS as an ordinary network error, not an app crash. */
final class FailedNetworkCall implements Call {
    private final Request request;
    private final IOException failure;
    private final Timeout timeout = new Timeout();
    private boolean executed;
    private volatile boolean canceled;
    FailedNetworkCall(Request request, Throwable cause) {
        this.request = request;
        failure = new IOException("Experimental TLS unavailable; disable its switches", cause);
    }
    @Override public Request request() { return request; }
    private synchronized void markExecuted() {
        if (executed) throw new IllegalStateException("Already Executed");
        executed = true;
    }
    @Override public Response execute() throws IOException { markExecuted(); throw failure; }
    @Override public void enqueue(Callback callback) { markExecuted(); callback.onFailure(this, failure); }
    @Override public void cancel() { canceled = true; }
    @Override public synchronized boolean isExecuted() { return executed; }
    @Override public boolean isCanceled() { return canceled; }
    @Override public Timeout timeout() { return timeout; }
    @Override public Call clone() { return new FailedNetworkCall(request, failure.getCause()); }
}
