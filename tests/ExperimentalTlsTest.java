package com.nova.iptv.advanced;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import okhttp3.*;
import org.conscrypt.Conscrypt;

/** Actual TLS sockets and ClientHello observations; no external services or trust bypasses. */
public final class ExperimentalTlsTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static final class RecordingOutput extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final List<Integer> writes = new ArrayList<>();
        final OutputStream wire;
        RecordingOutput(OutputStream wire) { this.wire = wire; }
        @Override public synchronized void write(int value) throws IOException { write(new byte[]{(byte)value},0,1); }
        @Override public synchronized void write(byte[] b, int off, int len) throws IOException {
            bytes.write(b,off,len); writes.add(len); if (wire != null) wire.write(b,off,len);
        }
        @Override public void flush() throws IOException { if (wire != null) wire.flush(); }
        @Override public void close() throws IOException { if (wire != null) wire.close(); }
    }
    private static KeyStore keys() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Paths.get("work/tls-test.p12"))) {
            store.load(in, "test-only-password".toCharArray());
        }
        return store;
    }
    private static X509TrustManager testTrust() throws Exception {
        TrustManagerFactory managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        managers.init(keys());
        return (X509TrustManager) managers.getTrustManagers()[0];
    }
    private static SSLContext clientContext(X509TrustManager trust) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS", Conscrypt.newProvider());
        context.init(null, new TrustManager[]{trust}, null);
        return context;
    }
    private static SSLContext serverContext() throws Exception {
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(keys(), "test-only-password".toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(km.getKeyManagers(), null, null);
        return context;
    }
    private static void streamTests() throws Exception {
        byte[] hello = new byte[605]; hello[0]=22; hello[1]=3; hello[2]=3;
        hello[3]=2; hello[4]=88; hello[5]=1;
        for (int i=6;i<hello.length;i++) hello[i]=(byte)i;
        for (int split : new int[]{1,2,4,5,6,257,605}) {
            RecordingOutput record = new RecordingOutput(null);
            ClientHelloOutputStream output = new ClientHelloOutputStream(record);
            for (int start=0;start<hello.length;start+=split) {
                output.write(hello,start,Math.min(split,hello.length-start));
            }
            check(Arrays.equals(hello,record.bytes.toByteArray()), "fragmentation preserves all TLS bytes at input split "+split);
            check(record.writes.get(0)==2 && record.writes.get(1)==3, "first TLS header split into 2/3 bytes");
            int n=record.writes.size(); output.write(new byte[8192]);
            check(record.writes.size()==n+1 && record.writes.get(n)==8192, "post-handshake media is not fragmented");
        }
        RecordingOutput plain = new RecordingOutput(null);
        ClientHelloOutputStream output = new ClientHelloOutputStream(plain);
        byte[] nonTls="not-a-TLS-record".getBytes("UTF-8"); output.write(nonTls);
        check(Arrays.equals(nonTls,plain.bytes.toByteArray()), "non-TLS bytes unchanged");
        RecordingOutput interrupted = new RecordingOutput(null);
        Thread.currentThread().interrupt();
        try { new ClientHelloOutputStream(interrupted).write(hello); throw new AssertionError("interruption ignored"); }
        catch (InterruptedIOException expected) { check(Thread.currentThread().isInterrupted(), "interrupt status preserved"); }
        finally { Thread.interrupted(); }
    }
    private static void handshake(boolean fragment, boolean omit, boolean continuous, String protocol) throws Exception {
        SSLServerSocket server=(SSLServerSocket)serverContext().getServerSocketFactory()
            .createServerSocket(0,1,InetAddress.getLoopbackAddress());
        server.setEnabledProtocols(new String[]{protocol}); server.setSoTimeout(8000);
        CompletableFuture<List<SNIServerName>> observed = new CompletableFuture<>();
        Thread worker=new Thread(() -> {
            try (SSLServerSocket listener=server; SSLSocket peer=(SSLSocket)listener.accept()) {
                peer.setSoTimeout(8000); peer.startHandshake();
                List<SNIServerName> names=((ExtendedSSLSession)peer.getSession()).getRequestedServerNames();
                byte[] received=new byte[1024]; new DataInputStream(peer.getInputStream()).readFully(received);
                for (int i=0;i<received.length;i++) if (received[i]!=(byte)i) throw new IOException("echo payload mismatch");
                peer.getOutputStream().write(7); peer.getOutputStream().flush();
                observed.complete(names);
            } catch (Throwable e) { observed.completeExceptionally(e); }
        },"tls-test-echo");
        worker.setDaemon(true); worker.start();
        Socket raw=new Socket(InetAddress.getLoopbackAddress(),server.getLocalPort()); raw.setSoTimeout(8000);
        RecordingOutput wire=new RecordingOutput(raw.getOutputStream());
        Socket recording=new ForwardingSocket(raw) { @Override public OutputStream getOutputStream() { return wire; } };
        ExperimentalTlsFactory factory=new ExperimentalTlsFactory(clientContext(testTrust()).getSocketFactory(),fragment,omit,continuous);
        try (SSLSocket tls=(SSLSocket)factory.createSocket(recording,"localhost",server.getLocalPort(),true)) {
            tls.setEnabledProtocols(new String[]{protocol}); tls.startHandshake();
            if (fragment && !omit) {
                byte[] hello=wire.bytes.toByteArray();
                int recordLength=5+((hello[3]&255)<<8)+(hello[4]&255);
                int target=ClientHelloOutputStream.sniSplitOffset(hello,recordLength);
                check(target>0,"actual TLS ClientHello SNI parsed");
                int total=0; boolean boundary=false;
                for (int write:wire.writes) { total+=write; if (total==target) boundary=true; }
                check(boundary,"actual TLS socket split inside unchanged SNI hostname");
            }
            check(tls.getSession().getPeerCertificates().length>0, "trusted certificate chain validated");
            check(okhttp3.internal.tls.OkHostnameVerifier.INSTANCE.verify("localhost",tls.getSession()), "correct hostname verified");
            check(!okhttp3.internal.tls.OkHostnameVerifier.INSTANCE.verify("wrong.invalid",tls.getSession()), "wrong hostname rejected");
            byte[] payload=new byte[1024]; for (int i=0;i<payload.length;i++) payload[i]=(byte)i;
            for (int burst=0;burst<2;burst++) {
                int start=wire.writes.size();
                tls.getOutputStream().write(payload,burst*512,512); tls.getOutputStream().flush();
                if (continuous) {
                    List<Integer> split=wire.writes.subList(start,wire.writes.size());
                    check(split.size()>5,"encrypted application data still split after handshake and on later writes");
                    check(split.stream().allMatch(n -> n>0 && n<=100),"ciphertext write fragments stay bounded");
                }
            }
            check(tls.getInputStream().read()==7, "encrypted application data round-trip");
            List<SNIServerName> names=observed.get(10,TimeUnit.SECONDS);
            check(omit ? names.isEmpty() : names.size()==1 && ((SNIHostName)names.get(0)).getAsciiName().equals("localhost"), "server observed expected SNI state");
            check(!fragment || wire.writes.get(0)==2 && wire.writes.get(1)==3, "engine actually used fragmented stream");
        } finally { raw.close(); server.close(); worker.join(1000); }
    }
    private static void routerTests() throws Exception {
        NetworkPolicy policy=new NetworkPolicy();
        TlsClientRouter router=new TlsClientRouter(policy);
        OkHttpClient base=policy.configure(new OkHttpClient.Builder().readTimeout(17,TimeUnit.SECONDS)).build();
        check(router.select(base)==base,"disabled modes return original client exactly");
        List<OkHttpClient> variants=new ArrayList<>();
        for (int mode=1;mode<8;mode++) {
            policy.update(new NetworkPolicy.Config(false,"",false,"",0,(mode&1)!=0,(mode&2)!=0,(mode&4)!=0,false));
            OkHttpClient next=router.select(base); variants.add(next);
            check(next.hostnameVerifier()==base.hostnameVerifier(),"default hostname verifier preserved");
            check(next.x509TrustManager()==base.x509TrustManager(),"original trust manager preserved");
            check(next.certificatePinner().equals(base.certificatePinner()),"certificate pins preserved");
            check(next.readTimeoutMillis()==17000,"timeouts preserved");
            check(next.connectionPool()!=base.connectionPool(),"experimental pool isolated from default");
            check(next.sslSocketFactory() instanceof ExperimentalTlsFactory,"experimental factory installed");
            check((next.socketFactory() instanceof ContinuousSocketFactory)==((mode&4)!=0),"continuous raw socket factory installed only when selected");
            check(router.select(base)==next,"same mode reuses its own client");
        }
        check(variants.get(0).connectionPool()!=variants.get(1).connectionPool()
            && variants.get(1).connectionPool()!=variants.get(2).connectionPool(),"mode combinations use independent pools");
        policy.update(new NetworkPolicy.Config(false,"",false,"",0));
        check(router.select(base)==base,"disabling returns original factory and pool");
        Request request=new Request.Builder().url("https://example.com/").build();
        FailedNetworkCall failed=new FailedNetworkCall(request,new IllegalStateException("test"));
        failed.cancel(); check(failed.isCanceled(),"failed call cancellation supported");
        try { failed.execute(); throw new AssertionError("failed call succeeded"); }
        catch (IOException expected) { check(failed.isExecuted(),"provider failure becomes IOException"); }
        check(!failed.clone().isExecuted(),"cloning failed call resets execution state");
    }
    private static void certificateRejection(boolean wrongHost, boolean fragment, boolean omit, boolean continuous) throws Exception {
        SSLServerSocket server=(SSLServerSocket)serverContext().getServerSocketFactory()
            .createServerSocket(0,1,InetAddress.getLoopbackAddress());
        server.setSoTimeout(6000);
        Thread worker=new Thread(() -> {
            try (SSLServerSocket listener=server; SSLSocket peer=(SSLSocket)listener.accept()) {
                peer.setSoTimeout(6000); peer.startHandshake(); peer.getInputStream().read();
            } catch (Exception expected) { }
        },"tls-negative-test"); worker.setDaemon(true); worker.start();
        OkHttpClient.Builder builder=new OkHttpClient.Builder().dns(host -> Collections.singletonList(InetAddress.getLoopbackAddress()))
            .connectTimeout(3,TimeUnit.SECONDS).readTimeout(3,TimeUnit.SECONDS).callTimeout(6,TimeUnit.SECONDS);
        if (wrongHost) {
            X509TrustManager trust=testTrust();
            builder.sslSocketFactory(clientContext(trust).getSocketFactory(),trust);
        }
        NetworkPolicy policy=new NetworkPolicy();
        policy.update(new NetworkPolicy.Config(false,"",false,"",0,fragment,omit,continuous,false));
        OkHttpClient client=new TlsClientRouter(policy).select(builder.build());
        String host=wrongHost?"wrong.invalid":"localhost";
        try {
            client.newCall(new Request.Builder().url("https://"+host+":"+server.getLocalPort()+"/").build()).execute();
            throw new AssertionError("invalid TLS peer accepted");
        } catch (SSLPeerUnverifiedException e) {
            check(wrongHost,"hostname mismatch remains rejected by real OkHttp connection");
        } catch (SSLHandshakeException e) {
            check(!wrongHost,"untrusted chain remains rejected by real TLS handshake");
        } finally { server.close(); worker.join(1500); }
    }
    public static void main(String[] args) throws Exception {
        streamTests(); routerTests();
        for (String protocol : new String[]{"TLSv1.2","TLSv1.3"}) {
            for (boolean fragment : new boolean[]{false,true}) {
                for (boolean omit : new boolean[]{false,true}) {
                    for (boolean continuous : new boolean[]{false,true}) handshake(fragment,omit,continuous,protocol);
                }
            }
        }
        for (boolean fragment : new boolean[]{false,true}) {
            for (boolean omit : new boolean[]{false,true}) {
                for (boolean continuous : new boolean[]{false,true}) {
                    if (!fragment && !omit && !continuous) continue;
                    certificateRejection(true,fragment,omit,continuous);
                    certificateRejection(false,fragment,omit,continuous);
                }
            }
        }
        System.out.println("PASS: "+assertions+" TLS assertions, including actual TLS 1.2/1.3 handshakes, SNI observation, and negative certificate tests");
    }
}
