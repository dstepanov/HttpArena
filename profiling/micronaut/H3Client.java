import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.*;
import io.netty.handler.codec.quic.*;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.CharsetUtil;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class H3Client {
    public static void main(String[] a) throws Exception {
        if (a.length < 1 || a.length > 2) {
            throw new IllegalArgumentException("Usage: H3Client <path> [expected body]");
        }
        NioEventLoopGroup g = new NioEventLoopGroup(1);
        Channel ch = null;
        QuicChannel q = null;
        try {
            QuicSslContext ssl = QuicSslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols(Http3.supportedApplicationProtocols()).build();
            ChannelHandler codec = Http3.newQuicClientCodecBuilder().sslContext(ssl)
                .maxIdleTimeout(5, TimeUnit.SECONDS)
                .initialMaxData(10000000).initialMaxStreamDataBidirectionalLocal(1000000).build();
            ch = new Bootstrap().group(g).channel(NioDatagramChannel.class).handler(codec).bind(0).sync().channel();
            q = QuicChannel.newBootstrap(ch).handler(new Http3ClientConnectionHandler())
                .remoteAddress(new InetSocketAddress("127.0.0.1", 8443)).connect().get(5, TimeUnit.SECONDS);
            CompletableFuture<String> response = new CompletableFuture<>();
            QuicStreamChannel s = Http3.newRequestStream(q, new Http3RequestStreamInboundHandler() {
                private String status;
                private final ByteArrayOutputStream body = new ByteArrayOutputStream();

                protected void channelRead(ChannelHandlerContext c, Http3HeadersFrame f) {
                    if (f.headers().status() != null) status = f.headers().status().toString();
                }
                protected void channelRead(ChannelHandlerContext c, Http3DataFrame f) {
                    try { body.writeBytes(ByteBufUtil.getBytes(f.content())); }
                    finally { f.release(); }
                }
                protected void channelInputClosed(ChannelHandlerContext c) {
                    if ("200".equals(status)) response.complete(body.toString(CharsetUtil.UTF_8));
                    else response.completeExceptionally(new IllegalStateException("HTTP status: " + status));
                }
                public void exceptionCaught(ChannelHandlerContext c, Throwable cause) {
                    response.completeExceptionally(cause);
                    c.close();
                }
            }).get(5, TimeUnit.SECONDS);
            DefaultHttp3HeadersFrame h = new DefaultHttp3HeadersFrame();
            h.headers().method("GET").path(a[0]).authority("localhost:8443").scheme("https");
            s.writeAndFlush(h).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT).get(5, TimeUnit.SECONDS);
            String body = response.get(5, TimeUnit.SECONDS);
            if (a.length == 2 && !a[1].equals(body)) {
                throw new IllegalStateException("Unexpected response body: " + body);
            }
            System.out.println("status=200\nbody=" + body);
        } finally {
            if (q != null) q.close().sync();
            if (ch != null) ch.close().sync();
            g.shutdownGracefully(0, 5, TimeUnit.SECONDS).sync();
        }
    }
}
