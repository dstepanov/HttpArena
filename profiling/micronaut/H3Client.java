import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.*;
import io.netty.handler.codec.quic.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.CharsetUtil;
import java.net.InetSocketAddress;
import java.util.concurrent.CountDownLatch;

public class H3Client {
    public static void main(String[] a) throws Exception {
        NioEventLoopGroup g = new NioEventLoopGroup(1);
        QuicSslContext ssl = QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocols(Http3.supportedApplicationProtocols()).build();
        ChannelHandler codec = Http3.newQuicClientCodecBuilder().sslContext(ssl)
            .maxIdleTimeout(5000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .initialMaxData(10000000).initialMaxStreamDataBidirectionalLocal(1000000).build();
        Channel ch = new Bootstrap().group(g).channel(NioDatagramChannel.class).handler(codec).bind(0).sync().channel();
        QuicChannel q = QuicChannel.newBootstrap(ch).handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress("127.0.0.1", 8443)).connect().get();
        CountDownLatch done = new CountDownLatch(1);
        QuicStreamChannel s = Http3.newRequestStream(q, new Http3RequestStreamInboundHandler() {
            protected void channelRead(ChannelHandlerContext c, Http3HeadersFrame f) { System.out.println("status=" + f.headers().status()); }
            protected void channelRead(ChannelHandlerContext c, Http3DataFrame f) { System.out.println("body=" + f.content().toString(CharsetUtil.UTF_8)); f.release(); }
            protected void channelInputClosed(ChannelHandlerContext c) { done.countDown(); }
        }).sync().getNow();
        DefaultHttp3HeadersFrame h = new DefaultHttp3HeadersFrame();
        h.headers().method("GET").path(a[0]).authority("localhost:8443").scheme("https");
        s.writeAndFlush(h).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        done.await(5, java.util.concurrent.TimeUnit.SECONDS);
        System.exit(0);
    }
}
