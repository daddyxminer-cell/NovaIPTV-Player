package com.nova.iptv.advanced;

import java.io.*;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.*;
import okhttp3.*;

/** Verifies targeted split boundaries and protocol-safe HTTP field-name casing. */
public final class LocalShaperTest {
    private static int assertions;
    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
    private static final class Recording extends OutputStream {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        final List<Integer> boundaries = new ArrayList<>();
        boolean closed;
        @Override public void write(int b) { write(new byte[]{(byte)b},0,1); }
        @Override public void write(byte[] b,int off,int len) {
            bytes.write(b,off,len); boundaries.add(bytes.size());
        }
        @Override public void close() { closed=true; }
    }
    private static byte[] hello(String host, int filler) throws Exception {
        ByteArrayOutputStream extensions = new ByteArrayOutputStream();
        DataOutputStream ex = new DataOutputStream(extensions);
        ex.writeShort(21); ex.writeShort(filler); ex.write(new byte[filler]);
        if (host != null) {
            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            ex.writeShort(0); ex.writeShort(name.length+5); ex.writeShort(name.length+3);
            ex.writeByte(0); ex.writeShort(name.length); ex.write(name);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(body);
        out.writeShort(0x0303); out.write(new byte[32]); out.writeByte(0);
        out.writeShort(2); out.writeShort(0x1301); out.writeByte(1); out.writeByte(0);
        out.writeShort(extensions.size()); out.write(extensions.toByteArray());
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        out = new DataOutputStream(wire);
        out.writeByte(22); out.writeShort(0x0303); out.writeShort(body.size()+4);
        out.writeByte(1); out.writeByte(body.size()>>>16); out.writeShort(body.size()); out.write(body.toByteArray());
        return wire.toByteArray();
    }
    private static void sniTests() throws Exception {
        String host="stream.provider.example";
        for (int filler : new int[]{0,512,4096}) {
            byte[] hello=hello(host,filler);
            int split=ClientHelloOutputStream.sniSplitOffset(hello,hello.length);
            int start=new String(hello,StandardCharsets.US_ASCII).indexOf(host);
            check(split==start+host.length()/2,"split is strictly inside SNI, including SNI beyond the first 256 bytes");
            for (int inputChunk : new int[]{1,2,5,17,hello.length}) {
                Recording record=new Recording(); ClientHelloOutputStream shaped=new ClientHelloOutputStream(record);
                for (int pos=0;pos<hello.length;pos+=inputChunk) shaped.write(hello,pos,Math.min(inputChunk,hello.length-pos));
                check(Arrays.equals(hello,record.bytes.toByteArray()),"all ClientHello bytes preserved for arbitrary caller chunks");
                check(record.boundaries.contains(split),"socket write boundary cuts SNI hostname");
                int before=record.boundaries.size(); shaped.write(new byte[4096]);
                check(record.boundaries.size()==before+1,"handshake-only shaping does not split subsequent application data");
            }
            for (int length=0;length<hello.length;length++) {
                check(ClientHelloOutputStream.sniSplitOffset(hello,length)==-1,"truncated record is not parsed as complete SNI");
            }
        }
        byte[] absent=hello(null,0);
        check(ClientHelloOutputStream.sniSplitOffset(absent,absent.length)==-1,"SNI omission has no target");
        byte[] shortName=hello("x",0);
        check(ClientHelloOutputStream.sniSplitOffset(shortName,shortName.length)==-1,"single-byte name cannot be split inside");
        byte[] complete=hello(host,100);
        for (int partial : new int[]{1,4,5,12,complete.length-1}) {
            Recording record=new Recording(); ClientHelloOutputStream stream=new ClientHelloOutputStream(record);
            stream.write(complete,0,partial); stream.flush();
            check(record.bytes.size()==partial,"partial record flush does not stall handshake");
            stream.write(complete,partial,complete.length-partial);
            check(Arrays.equals(complete,record.bytes.toByteArray()),"partial flush fallback preserves stream");
        }
        Recording closeRecord=new Recording(); ClientHelloOutputStream closing=new ClientHelloOutputStream(closeRecord);
        closing.write(complete,0,3); closing.close();
        check(closeRecord.closed && closeRecord.bytes.size()==3,"close flushes bounded partial data and closes transport");
        Random random=new Random(121);
        for (int trial=0;trial<2000;trial++) {
            byte[] corrupted=complete.clone();
            for (int i=0;i<4;i++) corrupted[random.nextInt(corrupted.length)]=(byte)random.nextInt(256);
            int split=ClientHelloOutputStream.sniSplitOffset(corrupted,corrupted.length);
            check(split==-1 || split>5 && split<corrupted.length,"malformed inputs are bounds checked");
        }
    }
    private static boolean mixedHostName(Request request) {
        for (int i=0;i<request.headers().size();i++) if (request.headers().name(i).equals("hOsT")) return true;
        return false;
    }
    private static void hostTests() throws Exception {
        NetworkPolicy policy=new NetworkPolicy(); HostCaseInterceptor interceptor=new HostCaseInterceptor(policy);
        Request request=new Request.Builder().url("https://example.com:8443/a?q=1")
            .header("Host","example.com:8443").header("Authorization","test-only")
            .header("Range","bytes=42-").post(RequestBody.create(null,new byte[]{1,2,3})).build();
        check(interceptor.apply(request,Protocol.HTTP_1_1)==request,"off by default");
        policy.update(new NetworkPolicy.Config(false,"",false,"",0,false,false,false,false,true));
        Request changed=interceptor.apply(request,Protocol.HTTP_1_1);
        check(mixedHostName(changed),"HTTP/1.1 field-name spelling is actually mixed case");
        check(changed.headers("Host").size()==1 && changed.header("Host").equals("example.com:8443"),"value and port unchanged; no duplicate Host");
        check(changed.url().equals(request.url()) && changed.method().equals(request.method()) && changed.body()==request.body(),"routing, method and request body unchanged");
        check(changed.header("Authorization").equals("test-only") && changed.header("Range").equals("bytes=42-"),"provider headers preserved");
        check(interceptor.apply(request,Protocol.HTTP_2)==request,"HTTP/2 lowercase field-name requirements preserved");
        check(interceptor.apply(request,Protocol.H2_PRIOR_KNOWLEDGE)==request,"prior-knowledge HTTP/2 unchanged");
        check(interceptor.apply(request,null)==request,"unknown protocol left alone");
        check(interceptor.apply(request.newBuilder().removeHeader("Host").build(),Protocol.HTTP_1_1).header("Host")==null,"no host value invented");
        Request duplicates=request.newBuilder().addHeader("Host","other.example").build();
        check(interceptor.apply(duplicates,Protocol.HTTP_1_1)==duplicates,"ambiguous duplicate Host is not normalized");
        Connection connection=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},
            (proxy,method,args) -> { if (method.getName().equals("protocol")) return Protocol.HTTP_1_1; throw new UnsupportedOperationException(); });
        Interceptor.Chain chain=(Interceptor.Chain)Proxy.newProxyInstance(Interceptor.Chain.class.getClassLoader(),new Class<?>[]{Interceptor.Chain.class},
            (proxy,method,args) -> {
                if (method.getName().equals("connection")) return connection;
                if (method.getName().equals("request")) return request;
                if (method.getName().equals("proceed")) return new Response.Builder().request((Request)args[0]).code(200)
                    .message("OK").protocol(Protocol.HTTP_1_1).body(ResponseBody.create(null,new byte[0])).build();
                throw new UnsupportedOperationException();
            });
        try (Response response=interceptor.intercept(chain)) { check(mixedHostName(response.request()),"network interceptor uses actual negotiated protocol"); }
        policy.update(new NetworkPolicy.Config(false,"",false,"",0));
        check(interceptor.apply(request,Protocol.HTTP_1_1)==request,"existing interceptor sees disable immediately");
    }
    public static void main(String[] args) throws Exception {
        sniTests(); hostTests();
        System.out.println("PASS: "+assertions+" targeted SNI parser/split and HTTP Host casing assertions");
    }
}
