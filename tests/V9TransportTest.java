package com.nova.iptv.advanced;

import java.io.*;
import java.net.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import javax.net.SocketFactory;
import okhttp3.*;

/** Regression tests for v9 output shaping and HTTP-header-only padding. */
public final class V9TransportTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static class RecordingOutput extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final List<Integer> writes = new ArrayList<>();
        boolean closed;
        @Override public void write(int b) throws IOException { write(new byte[]{(byte)b},0,1); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            if (closed) throw new SocketException("test stream closed");
            bytes.write(b,off,len); writes.add(len);
        }
        @Override public void close() { closed=true; }
    }
    private static void fragmentTests() throws Exception {
        RecordingOutput sink=new RecordingOutput();
        ContinuousOutputStream out=new ContinuousOutputStream(sink,new Random(9));
        byte[] payload=new byte[16384]; new Random(11).nextBytes(payload);
        ByteArrayOutputStream expected=new ByteArrayOutputStream();
        Set<Integer> sizes=new HashSet<>();
        for (int call=0;call<3;call++) {
            int start=sink.writes.size(); out.write(payload,13,12000); expected.write(payload,13,12000);
            List<Integer> written=sink.writes.subList(start,sink.writes.size());
            check(written.size()>120,"all writes, including later stream bursts, remain split");
            for (int i=0;i<written.size();i++) {
                int n=written.get(i);
                check(n>0 && n<=100,"fragment has bounded positive size");
                if (i<written.size()-1) check(n>=20,"non-tail fragment is at least 20 bytes");
                sizes.add(n);
            }
        }
        check(sizes.size()>20,"fragment offsets are randomized");
        check(Arrays.equals(expected.toByteArray(),sink.bytes.toByteArray()),"arbitrary ciphertext/plaintext bytes preserved exactly");
        int before=sink.writes.size(); out.write(payload,0,0);
        check(sink.writes.size()==before,"empty writes produce no network bytes");
        out.write(255); check((sink.bytes.toByteArray()[sink.bytes.size()-1]&255)==255,"single-byte writes preserved");
        try { out.write(payload,-1,5); throw new AssertionError("bad range accepted"); }
        catch (IndexOutOfBoundsException expectedFailure) { assertions++; }
        try { out.write(null,0,1); throw new AssertionError("null accepted"); }
        catch (NullPointerException expectedFailure) { assertions++; }
        out.close();
        check(sink.closed,"close reaches underlying output");
        try { out.write(1); throw new AssertionError("closed write accepted"); }
        catch (SocketException expectedFailure) { assertions++; }
        Thread.currentThread().interrupt();
        try { new ContinuousOutputStream(new RecordingOutput()).write(payload); throw new AssertionError("interrupt ignored"); }
        catch (InterruptedIOException expectedFailure) {
            check(expectedFailure.bytesTransferred==0 && Thread.currentThread().isInterrupted(),"interruption retained and reported before transfer");
        } finally { Thread.interrupted(); }
        CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
        OutputStream blocked=new OutputStream() {
            @Override public void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
            @Override public void write(byte[] b,int off,int len) throws IOException {
                entered.countDown();
                try { release.await(); } catch (InterruptedException e) { throw new InterruptedIOException(); }
                throw new SocketException("closed by caller");
            }
            @Override public void close() { release.countDown(); }
        };
        ContinuousOutputStream closing=new ContinuousOutputStream(blocked);
        CompletableFuture<Boolean> ended=new CompletableFuture<>();
        Thread writer=new Thread(() -> {
            try { closing.write(payload); ended.complete(false); }
            catch (IOException e) { ended.complete(true); }
        }); writer.setDaemon(true); writer.start();
        check(entered.await(2,TimeUnit.SECONDS),"write reached underlying socket");
        closing.close();
        check(ended.get(2,TimeUnit.SECONDS),"close is not blocked by write lock and unblocks active write");
    }
    private static void factoryTests() throws Exception {
        class FakeSocket extends Socket {
            final RecordingOutput sink=new RecordingOutput();
            boolean noDelay,closed;
            @Override public void setTcpNoDelay(boolean value) { noDelay=value; }
            @Override public OutputStream getOutputStream() { return sink; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[]{7,8,9}); }
            @Override public void close() { closed=true; sink.close(); }
        }
        List<FakeSocket> sockets=new ArrayList<>();
        SocketFactory raw=new SocketFactory() {
            private Socket make() { FakeSocket s=new FakeSocket(); sockets.add(s); return s; }
            @Override public Socket createSocket() { return make(); }
            @Override public Socket createSocket(String h,int p) { return make(); }
            @Override public Socket createSocket(String h,int p,InetAddress l,int lp) { return make(); }
            @Override public Socket createSocket(InetAddress h,int p) { return make(); }
            @Override public Socket createSocket(InetAddress h,int p,InetAddress l,int lp) { return make(); }
        };
        SocketFactory factory=new ContinuousSocketFactory(raw); InetAddress local=InetAddress.getLoopbackAddress();
        List<Socket> variants=Arrays.asList(factory.createSocket(),factory.createSocket("localhost",80),
            factory.createSocket("localhost",80,local,0),factory.createSocket(local,80),factory.createSocket(local,80,local,0));
        for (int i=0;i<variants.size();i++) {
            Socket socket=variants.get(i); FakeSocket underlying=sockets.get(i);
            check(underlying.noDelay,"TCP_NODELAY requested for each factory overload");
            check(ContinuousSocket.wrap(socket)==socket,"layering TLS does not double-wrap shaped socket");
            check(socket.getOutputStream()==socket.getOutputStream(),"one output wrapper per socket");
            socket.getOutputStream().write(new byte[700]);
            check(underlying.sink.writes.size()>7,"plain HTTP transport output is split");
            check(socket.getInputStream().read()==7,"incoming payload stream is untouched");
            socket.close(); check(underlying.closed,"closing shaped socket closes delegate");
        }
    }
    private static void paddingTests() throws Exception {
        NetworkPolicy policy=new NetworkPolicy(); HttpPaddingInterceptor interceptor=new HttpPaddingInterceptor(policy);
        Request original=new Request.Builder().url("https://example.com/live?a=1")
            .post(RequestBody.create(null,new byte[]{0,1,2,3}))
            .header("Authorization","Bearer test-only").header("Referer","https://provider.invalid/")
            .header("User-Agent","Original/8").header("Range","bytes=10-").build();
        check(interceptor.apply(original)==original,"padding off leaves request completely unchanged");
        policy.update(new NetworkPolicy.Config(false,"",false,"",0,false,false,false,true));
        Set<String> values=new HashSet<>(); Set<Integer> lengths=new HashSet<>();
        for (int i=0;i<150;i++) {
            Request changed=interceptor.apply(original); String value=changed.header("X-Padding");
            check(value.length()>=32 && value.length()<=256,"HTTP padding length bounded");
            check(value.matches("[A-Za-z0-9_-]+"),"padding is safe single-line ASCII");
            check(changed.body()==original.body() && changed.url().equals(original.url())
                && changed.method().equals(original.method()),"no request-body/URL/method mutation");
            check(changed.header("Authorization").equals(original.header("Authorization"))
                && changed.header("Range").equals(original.header("Range"))
                && changed.header("Referer").equals(original.header("Referer"))
                && changed.header("User-Agent").equals(original.header("User-Agent")),"provider headers preserved");
            Request retry=interceptor.apply(changed);
            check(retry.headers("X-Padding").size()==1,"retries/redirects do not accumulate header padding");
            check(!retry.header("X-Padding").equals(value),"fresh padding for next wire request");
            values.add(value); lengths.add(value.length());
        }
        check(values.size()==150 && lengths.size()>20,"values and lengths vary per request");
        OkHttpClient client=policy.configure(new OkHttpClient.Builder()).build();
        check(client.networkInterceptors().stream().anyMatch(i -> i instanceof HttpPaddingInterceptor),"explicit network interceptor installed");
        Interceptor.Chain chain=(Interceptor.Chain)Proxy.newProxyInstance(Interceptor.Chain.class.getClassLoader(),
            new Class<?>[]{Interceptor.Chain.class},(proxy,method,args) -> {
                if (method.getName().equals("request")) return original;
                if (method.getName().equals("proceed")) return new Response.Builder().request((Request)args[0])
                    .code(200).message("OK").protocol(Protocol.HTTP_1_1).body(ResponseBody.create(null,new byte[0])).build();
                throw new UnsupportedOperationException(method.getName());
            });
        try (Response response=interceptor.intercept(chain)) {
            check(response.request().header("X-Padding")!=null,"interceptor injects padding at send time");
        }
        policy.update(new NetworkPolicy.Config(false,"",false,"",0));
        try (Response response=interceptor.intercept(chain)) {
            check(response.request().header("X-Padding")==null,"existing client stops generating padding when disabled");
        }
        Request providerPadding=original.newBuilder().header("X-Padding","provider-value").build();
        check(interceptor.apply(providerPadding)==providerPadding,"disabled option preserves provider-owned header");
    }
    public static void main(String[] args) throws Exception {
        fragmentTests(); factoryTests(); paddingTests();
        System.out.println("PASS: "+assertions+" v9 fragmentation, cancellation, factory and HTTP-padding assertions");
    }
}
