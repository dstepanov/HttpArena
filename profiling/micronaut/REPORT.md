# Micronaut 5.3.x on HttpArena: profile report

## 1. Setup

| Item | Value |
|---|---|
| Framework | micronaut-core `origin/5.3.x` at `cc5ad2be35` (5.3.0-SNAPSHOT), published to Maven Local with JDK 25 |
| Entries | `micronaut-tuned` and `micronaut-production`, branch `micronaut-async-db-fortunes`, built from copies against the snapshot BOM (repo poms untouched) |
| Runtime | JDK 27, tuned entry on io_uring, production entry on defaults |
| Server CPUs | 0-1 |
| Load CPUs | 2-3 (Postgres, for the two database paths, on CPU 3) |
| Profiler | async-profiler 4.4, `itimer`, 1 ms, 10 s window after about 10 s warmup |
| Runs | one run per path, not repeated |

Caveats: absolute numbers are low because the box is small and shared; relative shapes are what matter. The two database paths are limited by Postgres sharing a CPU. HTTP/3 was only checked functionally.

## 2. Throughput (req/s)

| Path | tuned | production |
|---|---|---|
| baseline GET | 142k | 105k |
| baseline mix (GET + POST bodies) | 137k | n/a |
| /baseline2 | 144k | n/a |
| pipelined, depth 16 | 569k | n/a |
| json | 38.8k | 30k |
| json-comp (gzip) | 13.6k | 10.6k |
| async /delay | 24.1k | n/a |
| static plain | 24.8k | 21.6k |
| static precompressed (br) | 12.4k | n/a |
| echo POST 10 KB | 81k | 56k |
| TLS h1 | 54k | n/a |
| h2 over TLS | 216k | n/a |
| h2c | 146k | n/a |
| WebSocket echo | 167k | n/a |
| async-db | 3.2k | 3.4k |
| fortunes | 4.5k | 3.9k |

The tuned-over-production gap is 25-45% on the CPU-bound paths and disappears on the database paths. Part of it is io_uring and part is the tuned JVM and server flags.

## 3. Findings by path

Frame shares are percentages of samples. "Syscall" is the kernel boundary (read/write/epoll/io_uring enter).

### Baseline, pipelined, async
- Syscall frame is about 65% of baseline samples. The rest is spread thin: `PipeliningServerHandler.channelRead` is about 22% inclusive, with `write0`, route matching (`UriRouteSet.findMethodMatches`), parameter decoding and `NettyHttpHeaders.validateHeader` each a few percent.
- Under pipelining `validateHeader` rises to about 4.5%. The idea of skipping it for the cached Date header was measured before at about 0.1% of a request and I do not recommend publishing it.
- Nothing new compared with the 5.2 profile. The framework is close to the syscall floor on these paths.

### JSON
- About 40% is the serializer: `SerializedString.appendQuotedUTF8` 6.8%, `UTF8JsonGenerator.writeName` 6.8%, `_writeStringSegment` 5.3%, plus `JacksonEncoder.serializableKey`. Field names are quoted and written again for every object.
- Candidate: pre-encode and reuse field-name tokens in the Serde Jackson encoder (Serde project, not core).

### JSON with gzip
- About 58% of samples are in `Compressor$Session.push`, nearly all of it zlib `deflate_slow` and `longest_match`. That is the compression cost itself; there is no meaningful framework overhead to remove.

### Static files
- The weakest path. Plain static gets 25k against 142k for baseline.
- Each request hops to a blocking executor (`InstrumentedExecutor.execute` 8.6%, `pthread_cond_signal` 7.7%, futex waits and `ForkJoinPool.deactivate`), so about 39% of samples sit under `PropagatedContextImpl.propagate`.
- It repeats `stat`, `open`, `statx` and `access` syscalls per request (each 2-5%) and reads through an `InputStream` (`SystemFileBodyWriter.write`, about 19% inclusive).
- Precompressed `.br` is about half as fast again, because the twin lookup adds more filesystem calls.
- Candidates: read small files inline on the event loop (branch `denis/static-small-files`, not pushed) and cache per-file metadata and the twin lookup in the entry's `StaticController`.

### Echo POST (8gbit profile)
- `PlatformDependent.allocateUninitializedArray` 5.5% and `NettyByteBuffer.toByteArray` about 8.6% are two body copies (buffer to array, array back to buffer). The earlier C2 fix already removed the streaming aggregation.
- Candidate: avoid the extra copy for byte[] bodies. Not investigated further.

### HTTP/2 (TLS and cleartext)
- h2 over TLS: 216k, h2c: 146k. Time sits in `Http2ServerHandler` (`channelRead`, `channelReadComplete`, `writeHeaders` 9-13%, `toHttp2Headers` 4-6%), Netty's HPACK decoder, promise notification and `DefaultHeaders.get`.
- No obvious Micronaut-specific waste. Header conversion is the largest framework-owned item.

### WebSocket echo
- 70% of samples are syscalls. Micronaut-side is small: `NettyServerWebSocketHandler.channelRead0` about 16% inclusive, `sendAsync` 3.7%, `DefaultUnmatchedRequestArgumentBinder.createSpecific` 2.9% (argument binding on every message).
- The executor, event and encoding overhead is in branch `denis/ws-message-overhead` (not pushed).

### Fortunes
- Tuned: `FortunesController.escape` is about 30% inclusive (7.9% self), because it appends one character at a time, plus `String.encodeUTF8_UTF16` 8.3% and `AbstractStringBuilder.append` 7.3%. Fix: append runs of safe characters in bulk and write bytes directly.
- Production: 58% inside the handler lambda. `CharSequenceBodyWriter` copying the rendered `String` into a buffer is 21%; Rocker's own render is about 18% (`DefaultHtmlStringify.escape` 4%). Fix: return `byte[]` from the template or use Rocker's byte-output form.
- The first fortunes run produced 993 errors only because my test Postgres had 100 connections against a pool of 256. With `max_connections=256`, as the harness sets it, there were none.

### async-db
- Dominated by Postgres and the driver's network reads and writes. The entry's own code is about 11%. Postgres on one shared CPU is the ceiling, so this says little about the entry.

### HTTP/3
- `/baseline2` returned 200 with the right sum and `/static/reset.css` returned 200 with the CSS over QUIC. No load test, because `h2load-h3` could not be built here.

## 4. Recommended actions

| # | Action | Where | Expected effect | Status |
|---|---|---|---|---|
| 1 | Bulk escaping in the tuned fortunes page | entry | large on fortunes | not done |
| 2 | Byte output for the production fortunes template | entry | removes a 21% copy | not done |
| 3 | Small static files read inline | core, `denis/static-small-files` | largest gain available on static | branch ready, not pushed |
| 4 | Cache static file metadata and twin lookup | entry | fewer syscalls per static request | not done |
| 5 | WebSocket per-message overhead | core, `denis/ws-message-overhead` | small | branch ready, not pushed |
| 6 | Pre-encoded JSON field names | Serde | up to a few percent on json | not investigated |
| 7 | Skip header validation for the Date header | core | about 0.1% | not recommended |

## 5. Files

In this directory: `run.sh` and `all.sh` (the drivers), `analyze.py` (stack summary), `load/echo10k.lua` (echo body), `H3Client.java` (HTTP/3 check), `nojavadoc.gradle` (init script used to publish the core snapshot) and `results/load-*.txt` (the load generator output of every run). The raw `.collapsed` stacks were too large to commit; rerun `all.sh` to regenerate them.

## 6. Appendix: raw numbers per path

Each section has the load generator output, then the analyzer output for the collapsed stacks (`an.py`): top self frames, top inclusive Micronaut, entry and Netty-codec frames, and the leaf-most framework frame.

Commands used are in `all.sh` (tuned) and `prod.sh` (production).

### production-async-db

```
# load
    Latency   37.61ms   35.40ms   52.40ms   79.60ms   99.70ms
  74922 requests in 22.00s, 74922 responses
  Throughput: 3.40K req/s
  Latency samples: 74922 / 74922 responses (100.0%)
# profile
total 1874
-- top self
 24.2% __GI___libc_write
 15.9% writev
  9.4% epoll_wait
  5.7% __GI___read
  0.9% sun/nio/ch/EPollSelectorImpl.processEvents
  0.9% io/vertx/sqlclient/internal/RowDescriptorBase$ColumnNames.indexOf
  0.8% [vdso]
  0.6% io/netty/channel/AbstractChannelHandlerContext.fireChannelRead
-- top inclusive micronaut/entry/netty
 17.2% io/micronaut/http/server/netty/handler/PipeliningServerHandler$$Lambda.0x00000000158cf400.run
 17.2% io/micronaut/http/server/netty/handler/FlushCoalescer.run
 17.2% io/micronaut/http/server/netty/handler/PipeliningServerHandler.flushNow
 13.5% httparena/AsyncDbController$$Lambda.0x00000000159f7400.handle
 13.4% httparena/AsyncDbController.lambda$query$0
 11.8% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 10.4% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 10.2% io/micronaut/core/execution/CompletableFutureExecutionFlowImpl.lambda$onComplete$0
 10.2% io/micronaut/http/server/netty/NettyRequestLifecycle$$Lambda.0x00000000159fac00.accept
 10.2% io/micronaut/core/execution/CompletableFutureExecutionFlowImpl$$Lambda.0x00000000159fb000.accept
-- leaf-most framework frame (self attributed)
 56.7% (no micronaut/entry frame)
 17.2% io/micronaut/http/server/netty/handler/PipeliningServerHandler.flushNow
  6.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler$OutboundAccessImpl.write
  6.5% httparena/AsyncDbController.query
  2.2% httparena/AsyncDbController.lambda$query$0
  1.5% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  0.4% io/micronaut/http/server/netty/AbstractNettyHttpRequest.decodeParameters
  0.4% io/micronaut/buffer/netty/NettyReadBufferFactory.buffer
```

### production-baseline-get

```
# load
    Latency     2.53ms    1.82ms  58.90ms   94.07%
    Req/Sec    53.07k    12.81k   82.53k    73.41%
  2322181 requests in 22.07s, 228.10MB read
Requests/sec: 105232.25
Transfer/sec:     10.34MB
# profile
total 4138
-- top self
 53.2% __GI___libc_write
  9.0% __GI___read
  2.0% epoll_wait
  1.2% io/netty/util/internal/RefCnt$VarHandleRefCnt.isLiveNonVolatile
  0.7% io/netty/channel/AbstractChannelHandlerContext.fireChannelRead
  0.7% [vdso]
  0.7% java/nio/Buffer$2.acquireSession
  0.6% io/netty/channel/nio/NioIoHandler.processSelectedKeysPlain
-- top inclusive micronaut/entry/netty
 57.2% io/netty/handler/codec/ByteToMessageDecoder.channelReadComplete
 57.0% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
 25.7% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 21.0% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 20.3% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 20.0% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 19.6% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 19.5% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 18.4% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 18.3% io/micronaut/core/propagation/PropagatedContextImpl.propagate
-- leaf-most framework frame (self attributed)
 56.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
 22.7% (no micronaut/entry frame)
  3.4% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  2.2% io/micronaut/http/server/netty/AbstractNettyHttpRequest.decodeParameters
  0.8% io/micronaut/http/uri/UriTemplateMatcher.tryMatch
  0.8% io/micronaut/buffer/netty/NettyReadBufferFactory.copyOf
  0.5% io/micronaut/http/netty/NettyHttpParameters.<init>
  0.4% io/micronaut/http/uri/UriTemplateMatcher.matchSegments
```

### production-echo

```
# load
    Latency     1.38ms    1.42ms  32.24ms   91.10%
    Req/Sec    28.35k    10.84k   50.41k    68.18%
  1240572 requests in 22.03s, 11.97GB read
Requests/sec:  56318.86
Transfer/sec:    556.38MB
# profile
total 4183
-- top self
 47.9% writev
  8.8% __GI___read
  3.6% io/netty/util/internal/PlatformDependent.allocateUninitializedArray
  2.0% jint_disjoint_arraycopy_stub (stub gen)
  1.5% epoll_wait
  0.8% java/lang/StringLatin1.equals
  0.6% io/netty/util/AsciiString.contentEqualsIgnoreCase
  0.6% jdk/internal/foreign/MemorySessionImpl.checkValidStateRaw
-- top inclusive micronaut/entry/netty
 50.6% io/netty/handler/codec/ByteToMessageDecoder.channelReadComplete
 50.4% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
 29.7% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 23.8% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 23.2% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 22.3% io/micronaut/http/server/netty/handler/PipeliningServerHandler$OptimisticBufferingInboundHandler.read
 21.7% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 21.7% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 21.0% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 20.8% io/micronaut/core/propagation/PropagatedContextImpl.propagate
-- leaf-most framework frame (self attributed)
 50.3% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
 26.4% (no micronaut/entry frame)
  5.5% io/micronaut/buffer/netty/NettyByteBuffer.toByteArray
  3.3% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  1.2% io/micronaut/buffer/netty/NettyReadBufferFactory.copyOf
  0.7% io/micronaut/http/RouteMetadataAttributes.isMetadataKey
  0.7% io/micronaut/http/netty/NettyHttpHeaders.get
  0.5% io/micronaut/http/server/netty/ServerIdleStateHandler.write
```

### production-fortunes

```
# load
    Latency   32.89ms   31.10ms   47.70ms   67.40ms   88.60ms
  85637 requests in 22.00s, 85574 responses
  Throughput: 3.89K req/s
  Latency samples: 85574 / 85574 responses (100.0%)
# profile
total 4487
-- top self
  8.5% __GI___libc_write
  7.3% jdk/internal/foreign/MemorySessionImpl.checkValidStateRaw
  5.1% writev
  4.6% __GI___read
  4.0% com/fizzed/rocker/runtime/DefaultHtmlStringify.escape
  3.7% java/lang/String.decodeUTF8_UTF16
  3.7% jlong_disjoint_arraycopy_stub (stub gen)
  3.4% java/nio/Buffer.session
-- top inclusive micronaut/entry/netty
 58.4% httparena/FortunesController$$Lambda.0x00000000619f6400.handle
 58.4% httparena/FortunesController.lambda$fortunes$0
 25.3% io/micronaut/http/server/netty/NettyRequestLifecycle.lambda$handleNormal$0
 25.3% io/micronaut/core/execution/CompletableFutureExecutionFlowImpl$$Lambda.0x00000000619fd000.accept
 25.3% io/micronaut/core/execution/CompletableFutureExecutionFlowImpl.lambda$onComplete$0
 25.3% io/micronaut/http/server/netty/NettyRequestLifecycle$$Lambda.0x00000000619fcc00.accept
 25.3% io/micronaut/http/server/netty/RoutingInBoundHandler.writeResponse
 21.5% io/micronaut/http/server/ResponseLifecycle.encodeHttpResponseSafe
 21.5% io/micronaut/http/server/ResponseLifecycle.encodeHttpResponse
 21.5% io/micronaut/http/server/ResponseLifecycle.encodeBody
-- leaf-most framework frame (self attributed)
 33.9% (no micronaut/entry frame)
 21.2% io/micronaut/buffer/netty/NettyReadBufferFactory.copyOf
 18.3% views/fortunes$Template.__doRender
 13.1% httparena/FortunesController.lambda$fortunes$0
  5.4% io/micronaut/http/server/netty/handler/PipeliningServerHandler.flushNow
  3.4% io/micronaut/http/server/netty/handler/PipeliningServerHandler$OutboundAccessImpl.write
  1.2% views/fortunes.buildTemplate
  0.8% httparena/FortunesController.fortunes
```

### production-json-comp

```
# load
    Latency   24.28ms   22.00ms   30.90ms   54.20ms   77.20ms
  232211 requests in 22.00s, 232211 responses
  Throughput: 10.55K req/s
  Latency samples: 232211 / 232211 responses (100.0%)
# profile
total 3995
-- top self
 17.7% writev
 17.6% deflate_slow
 15.5% longest_match
  5.9% compress_block
  5.2% pqdownheap
  2.5% build_tree
  1.7% tools/jackson/core/json/UTF8JsonGenerator._writeStringSegment
  1.3% __GI___read
-- top inclusive micronaut/entry/netty
 77.0% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 75.6% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 75.5% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 75.3% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 75.2% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 75.2% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 75.0% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 74.8% io/micronaut/core/propagation/ThreadContext.propagate
 74.8% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 74.8% io/micronaut/http/server/netty/RoutingInBoundHandler$$Lambda.0x000000004f905800.run
-- leaf-most framework frame (self attributed)
 50.6% io/micronaut/http/server/netty/handler/Compressor$Session.push
 18.5% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
  5.9% (no micronaut/entry frame)
  3.0% io/micronaut/serde/jackson/JacksonEncoder.encodeString
  2.8% io/micronaut/http/server/netty/handler/Compressor$Session.finish
  2.5% io/micronaut/serde/jackson/JacksonEncoder.encodeKey
  1.6% io/micronaut/serde/jackson/JacksonEncoder.encodeLong
  1.5% io/micronaut/http/server/netty/handler/Compressor.prepare
```

### production-json

```
# load
    Latency   8.54ms   7.57ms   11.60ms   15.60ms   25.10ms
  659732 requests in 22.00s, 659429 responses
  Throughput: 29.97K req/s
  Latency samples: 659429 / 659429 responses (100.0%)
# profile
total 4226
-- top self
 34.6% writev
  5.8% tools/jackson/core/json/UTF8JsonGenerator._writeStringSegment
  4.7% tools/jackson/core/json/UTF8JsonGenerator.writeName
  3.2% tools/jackson/core/io/SerializedString.appendQuotedUTF8
  3.1% __GI___read
  2.2% io/micronaut/serde/jackson/JacksonEncoder.serializableKey
  2.1% tools/jackson/core/json/JsonWriteContext.writeName
  2.0% tools/jackson/core/json/JsonWriteContext.writeValue
-- top inclusive micronaut/entry/netty
 54.5% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 52.6% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 52.1% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 51.8% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 51.7% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 51.6% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 51.1% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 51.0% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 50.9% io/micronaut/core/propagation/ThreadContext.propagate
 50.9% io/micronaut/http/server/netty/RoutingInBoundHandler$$Lambda.0x0000000048905400.run
-- leaf-most framework frame (self attributed)
 36.2% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
 11.1% (no micronaut/entry frame)
 11.0% io/micronaut/serde/jackson/JacksonEncoder.encodeKey
  9.7% io/micronaut/serde/jackson/JacksonEncoder.encodeString
  5.2% io/micronaut/serde/jackson/JacksonEncoder.encodeLong
  2.4% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  2.2% io/micronaut/serde/jackson/JacksonEncoder.serializableKey
  1.4% io/micronaut/serde/jackson/JacksonEncoder$ReuseChildEncoder.finishStructureToken
```

### production-static-plain

```
# load
    Latency    11.84ms    4.29ms  67.43ms   77.64%
    Req/Sec    10.93k     1.76k   18.18k    69.25%
  477810 requests in 22.08s, 3.75GB read
Requests/sec:  21635.46
Transfer/sec:    174.10MB
# profile
total 4495
-- top self
 18.6% writev
  8.1% ___pthread_cond_signal
  6.6% __GI___futex_abstimed_wait_cancelable64
  4.6% __GI___read
  4.0% __xstat64
  3.8% __GI___open64
  3.2% __GI___libc_write
  2.8% statx
-- top inclusive micronaut/entry/netty
 40.3% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 40.2% io/micronaut/core/propagation/ThreadContext.propagate
 39.6% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 38.4% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 38.1% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 37.9% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 37.7% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 37.7% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 37.4% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 36.9% io/micronaut/http/server/netty/RoutingInBoundHandler$$Lambda.0x000000004f905400.run
-- leaf-most framework frame (self attributed)
 24.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler.flushNow
 21.5% (no micronaut/entry frame)
  9.9% io/micronaut/http/server/body/SystemFileBodyWriter.write
  8.5% io/micronaut/scheduling/instrument/InstrumentedExecutor.execute
  4.1% httparena/StaticController.file
  3.6% io/micronaut/http/server/netty/ServerIdleStateHandler.write
  3.0% io/micronaut/http/server/types/files/SystemFile.getLength
  2.9% io/micronaut/http/server/types/files/SystemFile.getLastModified
```

### tuned-async-db

```
# load
    Latency   39.70ms   36.80ms   59.00ms   92.80ms   122.80ms
  70918 requests in 22.00s, 70913 responses
  Throughput: 3.22K req/s
  Latency samples: 70913 / 70913 responses (100.0%)
# profile
total 2180
-- top self
 19.9% __GI___libc_write
 19.7% syscall
  4.4% epoll_wait
  3.5% io/vertx/sqlclient/impl/RowSetImpl$1.hasNext
  2.6% __GI___read
  1.1% tools/jackson/core/json/UTF8JsonGenerator._writeStringSegment
  0.9% io/vertx/pgclient/impl/codec/DataTypeCodec.binaryDecodeINT4
  0.8% io/vertx/pgclient/impl/codec/RowResultDecoder.decodeRow
-- top inclusive micronaut/entry/netty
 19.6% httparena/AsyncDbController.lambda$query$0
 19.6% httparena/AsyncDbController$$Lambda.0x00000000889eb000.handle
 13.7% io/micronaut/http/server/netty/NettyRequestLifecycle.lambda$handleNormal$0
 13.7% io/micronaut/http/server/netty/NettyRequestLifecycle$$Lambda.0x00000000889f1800.accept
 13.7% io/micronaut/core/execution/CompletableFutureExecutionFlowImpl$$Lambda.0x00000000889f1c00.accept
 13.7% io/micronaut/core/execution/CompletableFutureExecutionFlowImpl.lambda$onComplete$0
 13.5% io/micronaut/http/server/netty/RoutingInBoundHandler.writeResponse
 10.0% io/netty/handler/codec/ByteToMessageDecoder.channelRead
  9.2% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
  9.2% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
-- leaf-most framework frame (self attributed)
 68.7% (no micronaut/entry frame)
  6.0% httparena/AsyncDbController.query
  5.5% io/micronaut/http/server/netty/handler/PipeliningServerHandler$OutboundAccessImpl.write
  5.4% httparena/AsyncDbController.lambda$query$0
  1.8% io/micronaut/serde/jackson/JacksonEncoder.encodeString
  1.6% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  1.4% io/micronaut/serde/jackson/JacksonEncoder.encodeKey
  0.9% io/micronaut/serde/jackson/JacksonEncoder.encodeLong
```

### tuned-async

```
# load
    Latency   10.64ms   10.40ms   11.00ms   13.80ms   38.80ms
  529964 requests in 22.00s, 529964 responses
  Throughput: 24.09K req/s
  Latency samples: 529964 / 529964 responses (100.0%)
# profile
total 1589
-- top self
 58.7% syscall
  1.3% io/netty/util/concurrent/AbstractScheduledEventExecutor$1.compare
  0.9% io/micronaut/http/server/RequestLifecycle$4.provideResponse
  0.8% [vdso]
  0.8% io/micronaut/http/server/netty/handler/PipeliningServerHandler$OutboundAccessImpl.preprocess
  0.8% io/netty/util/internal/RefCnt$VarHandleRefCnt.isLiveNonVolatile
  0.8% io/netty/channel/DefaultChannelPipeline.channel
  0.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler.exposeResponseRequest
-- top inclusive micronaut/entry/netty
 17.6% httparena/BenchmarkController$$Lambda.0x0000000098967800.call
 17.6% httparena/BenchmarkController.lambda$delay$1
 13.8% io/micronaut/http/server/netty/NettyRequestLifecycle.lambda$handleNormal$0
 13.8% io/micronaut/http/server/netty/NettyRequestLifecycle$$Lambda.0x0000000098961000.accept
 13.8% io/micronaut/core/execution/CompletableFutureExecutionFlowImpl.lambda$onComplete$0
 13.8% io/micronaut/core/execution/CompletableFutureExecutionFlowImpl$$Lambda.0x0000000098961400.accept
 13.8% io/micronaut/http/server/netty/RoutingInBoundHandler.writeResponse
 12.8% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 10.4% io/micronaut/http/server/netty/RoutingInBoundHandler$$Lambda.0x000000009895e400.accept
 10.4% io/micronaut/core/execution/ImperativeExecutionFlowImpl.onComplete
-- leaf-most framework frame (self attributed)
 71.6% (no micronaut/entry frame)
  6.9% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  1.6% httparena/BenchmarkController.lambda$delay$1
  1.4% io/micronaut/http/server/netty/NettyResponseLifecycle.<init>
  1.3% io/micronaut/http/server/netty/handler/PipeliningServerHandler.flushNow
  0.9% io/micronaut/http/server/RequestLifecycle$4.provideResponse
  0.9% io/micronaut/http/server/netty/handler/PipeliningServerHandler$OutboundAccessImpl.preprocess
  0.9% io/micronaut/http/netty/body/NettyByteBodyFactory.<init>
```

### tuned-baseline-get

```
# load
    Latency    14.42ms   79.83ms 774.75ms   97.11%
    Req/Sec    73.84k    13.29k  126.42k    77.23%
  3135663 requests in 22.09s, 308.01MB read
Requests/sec: 141974.72
Transfer/sec:     13.95MB
# profile
total 4573
-- top self
 64.6% syscall
  1.3% io/netty/util/internal/shaded/org/jctools/queues/varhandle/VarHandleQueueUtil.soRefElement
  0.6% java/lang/String.equals
  0.5% java/lang/invoke/VarHandleInts$FieldInstanceReadWrite.compareAndSet
  0.5% io/netty/handler/codec/DefaultHeaders.get
  0.5% io/netty/channel/AbstractChannelHandlerContext.fireChannelRead
  0.4% io/micronaut/web/router/UriRouteSet.findMethodMatches
  0.4% io/netty/channel/uring/IoUringIoHandler$DefaultIoUringIoRegistration.handle
-- top inclusive micronaut/entry/netty
 27.1% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 22.2% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 21.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 21.5% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 21.1% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 21.1% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 20.2% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 20.0% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 20.0% io/micronaut/core/propagation/ThreadContext.propagate
 19.5% io/micronaut/http/server/netty/RoutingInBoundHandler.lambda$handleNormal$0
-- leaf-most framework frame (self attributed)
 76.8% (no micronaut/entry frame)
  4.2% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  2.1% io/micronaut/http/server/netty/AbstractNettyHttpRequest.decodeParameters
  1.2% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
  0.9% io/micronaut/http/uri/UriTemplateMatcher.tryMatch
  0.6% io/micronaut/buffer/netty/NettyReadBufferFactory.copyOf
  0.5% io/micronaut/http/netty/NettyHttpParameters.<init>
  0.5% io/micronaut/web/router/UriRouteSet.findMethodMatches
```

### tuned-baseline-mix

```
# load
    Latency   1.87ms   1.67ms   2.68ms   5.03ms   9.57ms
  3018193 requests in 22.00s, 3018193 responses
  Throughput: 137.17K req/s
  Latency samples: 3018193 / 3018193 responses (100.0%)
# profile
total 4577
-- top self
 59.1% syscall
  1.1% io/netty/util/internal/RefCnt$VarHandleRefCnt.release0
  1.0% io/netty/channel/uring/IoUringIoHandler.handleFastPath
  0.8% java/nio/Buffer.session
  0.7% jdk/internal/foreign/MemorySessionImpl.checkValidStateRaw
  0.6% java/nio/DirectByteBuffer.get
  0.5% io/netty/util/AsciiString.contentEqualsIgnoreCase
  0.5% io/netty/channel/uring/IoUringIoHandler$DefaultIoUringIoRegistration.handle
-- top inclusive micronaut/entry/netty
 32.4% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 25.2% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 24.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 22.8% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 22.7% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 21.8% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 21.8% io/micronaut/core/propagation/ThreadContext.propagate
 21.8% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 21.1% io/micronaut/http/server/netty/RoutingInBoundHandler$$Lambda.0x0000000021935c00.run
 21.1% io/micronaut/http/server/netty/RoutingInBoundHandler.lambda$handleNormal$0
-- leaf-most framework frame (self attributed)
 74.1% (no micronaut/entry frame)
  5.4% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  1.6% io/micronaut/http/server/netty/AbstractNettyHttpRequest.decodeParameters
  1.0% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
  0.6% io/micronaut/http/server/util/PerSecondCache.now
  0.5% io/micronaut/http/uri/UriTemplateMatcher.tryMatch
  0.5% io/micronaut/http/server/netty/handler/PipeliningServerHandler$OptimisticBufferingInboundHandler.read
  0.5% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
```

### tuned-baseline2

```
# load
    Latency     1.91ms    2.54ms  86.26ms   97.61%
    Req/Sec    72.35k    13.66k  103.90k    72.95%
  3169000 requests in 22.07s, 308.26MB read
Requests/sec: 143582.93
Transfer/sec:     13.97MB
# profile
total 4586
-- top self
 63.8% syscall
  0.7% java/nio/DirectByteBuffer.put
  0.6% jdk/internal/foreign/MemorySessionImpl.checkValidStateRaw
  0.6% io/netty/handler/codec/DefaultHeaders.get
  0.5% io/micronaut/http/server/netty/NettyHttpRequest.getAttribute
  0.5% java/lang/String.equals
  0.5% java/util/concurrent/atomic/AtomicLongFieldUpdater$CASUpdater.getAndAdd
  0.4% io/micronaut/web/router/AbstractRouteMatch.<init>
-- top inclusive micronaut/entry/netty
 29.7% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 25.9% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 25.2% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 24.9% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 24.6% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 24.5% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 23.9% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 23.7% io/micronaut/core/propagation/ThreadContext.propagate
 23.7% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 23.3% io/micronaut/http/server/netty/RoutingInBoundHandler.lambda$handleNormal$0
-- leaf-most framework frame (self attributed)
 73.7% (no micronaut/entry frame)
  6.9% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  1.6% io/micronaut/http/server/netty/AbstractNettyHttpRequest.decodeParameters
  0.9% io/micronaut/buffer/netty/NettyReadBufferFactory.copyOf
  0.8% io/micronaut/http/uri/UriTemplateMatcher.tryMatch
  0.8% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
  0.6% io/micronaut/http/server/netty/NettyHttpRequest.getAttribute
  0.5% io/micronaut/core/convert/value/ConvertibleMultiValuesMap.get
```

### tuned-echo

```
# load
    Latency     0.90ms    1.45ms  46.57ms   96.99%
    Req/Sec    40.74k     7.12k   61.44k    72.95%
  1783341 requests in 22.02s, 17.20GB read
Requests/sec:  80981.12
Transfer/sec:    800.02MB
# profile
total 4477
-- top self
 61.0% syscall
  5.5% io/netty/util/internal/PlatformDependent.allocateUninitializedArray
  2.8% jint_disjoint_arraycopy_stub (stub gen)
  0.7% jdk/internal/misc/ScopedMemoryAccess.putByte
  0.7% java/lang/StringLatin1.equals
  0.6% jdk/internal/foreign/MemorySessionImpl.checkValidStateRaw
  0.6% java/lang/invoke/VarHandleInts$FieldInstanceReadWrite.compareAndSet
  0.5% java/nio/DirectByteBuffer.put
-- top inclusive micronaut/entry/netty
 32.2% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 26.7% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 26.3% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 25.6% io/micronaut/http/server/netty/handler/PipeliningServerHandler$OptimisticBufferingInboundHandler.read
 24.7% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 24.7% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 24.3% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 24.3% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 24.2% io/micronaut/core/propagation/ThreadContext.propagate
 24.0% io/micronaut/http/server/netty/NettyRequestLifecycle.handleNormal
-- leaf-most framework frame (self attributed)
 71.9% (no micronaut/entry frame)
  8.6% io/micronaut/buffer/netty/NettyByteBuffer.toByteArray
  4.6% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  1.6% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
  1.5% io/micronaut/buffer/netty/NettyReadBufferFactory.copyOf
  0.6% io/micronaut/http/server/netty/handler/PipeliningServerHandler$OptimisticBufferingInboundHandler.read
  0.6% io/micronaut/http/server/util/PerSecondCache.now
  0.5% io/micronaut/http/server/netty/NettyHttpRequest.getAttribute
```

### tuned-fortunes

```
# load
    Latency   28.70ms   27.10ms   41.40ms   60.30ms   84.90ms
  98092 requests in 22.00s, 98022 responses
  Throughput: 4.45K req/s
  Latency samples: 98022 / 98022 responses (100.0%)
# profile
total 4302
-- top self
 10.6% __GI___libc_write
  9.8% syscall
  8.3% java/lang/String.encodeUTF8_UTF16
  7.9% httparena/FortunesController.escape
  7.3% java/lang/AbstractStringBuilder.append
  5.5% __GI___read
  4.8% java/lang/AbstractStringBuilder.needsNewBuffer
  3.9% java/lang/String.length
-- top inclusive micronaut/entry/netty
 53.4% httparena/FortunesController$$Lambda.0x00000000789e2800.handle
 53.4% httparena/FortunesController.lambda$fortunes$0
 29.6% httparena/FortunesController.escape
  4.8% io/micronaut/core/execution/CompletableFutureExecutionFlowImpl.lambda$onComplete$0
  4.8% io/micronaut/http/server/netty/NettyRequestLifecycle$$Lambda.0x00000000789e5c00.accept
  4.8% io/micronaut/core/execution/CompletableFutureExecutionFlowImpl$$Lambda.0x00000000789e8000.accept
  4.8% io/micronaut/http/server/netty/NettyRequestLifecycle.lambda$handleNormal$0
  4.7% io/micronaut/http/server/netty/RoutingInBoundHandler.writeResponse
  4.5% io/micronaut/http/server/netty/handler/PipeliningServerHandler$OutboundAccessImpl.write
  3.6% io/micronaut/http/server/netty/RoutingInBoundHandler$$Lambda.0x000000007895c400.accept
-- leaf-most framework frame (self attributed)
 43.6% (no micronaut/entry frame)
 29.6% httparena/FortunesController.escape
 18.5% httparena/FortunesController.lambda$fortunes$0
  3.5% io/micronaut/http/server/netty/handler/PipeliningServerHandler$OutboundAccessImpl.write
  1.1% httparena/FortunesController.fortunes
  0.8% io/micronaut/buffer/netty/NettyReadBufferFactory.copyOf
  0.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  0.2% io/micronaut/http/server/netty/handler/PipeliningServerHandler.flushNow
```

### tuned-h2-tls

```
# load
finished in 22.02s, 215556.36 req/s, 4.73MB/s
requests: 4742240 total, 4744288 started, 4742240 done, 4742240 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 4742240 2xx, 0 3xx, 0 4xx, 0 5xx
req/s           :    2697.84     4267.42     3367.78      671.93    76.56%
# profile
total 3822
-- top self
  6.0% syscall
  4.9% itable stub
  1.8% io/netty/util/concurrent/DefaultPromise.notifyListenersNow
  1.6% io/netty/handler/codec/http2/HpackHuffmanDecoder.processNibble
  1.5% io/netty/handler/codec/DefaultHeaders.get
  1.3% java/lang/StringLatin1.charAt
  1.3% io/netty/util/concurrent/DefaultPromise.checkNotifyWaiters
  1.2% io/netty/util/concurrent/DefaultPromise.isDone
-- top inclusive micronaut/entry/netty
 65.0% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 65.0% io/netty/handler/codec/ByteToMessageDecoder.decodeRemovalReentryProtection
 65.0% io/netty/handler/codec/ByteToMessageDecoder.callDecode
 63.3% io/micronaut/http/server/netty/handler/Http2ServerHandler$ConnectionHandler.channelRead
 63.3% io/netty/handler/codec/http2/Http2ConnectionHandler.decode
 63.3% io/netty/handler/codec/http2/Http2ConnectionHandler$FrameDecoder.decode
 63.3% io/netty/handler/codec/http2/DecoratingHttp2ConnectionDecoder.decodeFrame
 63.3% io/netty/handler/codec/http2/DefaultHttp2ConnectionDecoder.decodeFrame
 63.3% io/netty/handler/codec/http2/DefaultHttp2FrameReader.readFrame
 62.6% io/netty/handler/codec/http2/DefaultHttp2FrameReader.processPayloadState
-- leaf-most framework frame (self attributed)
 26.1% (no micronaut/entry frame)
 12.5% io/micronaut/http/server/netty/handler/Http2ServerHandler$ConnectionHandler.channelRead
 10.2% io/micronaut/http/server/netty/handler/Http2ServerHandler$ConnectionHandler.channelReadComplete
  8.8% io/micronaut/http/server/netty/handler/Http2ServerHandler$Http2Stream.writeHeaders
  6.9% io/micronaut/http/server/netty/handler/Http2ServerHandler.onHeadersRead
  5.9% io/micronaut/http/server/netty/handler/Http2ServerHandler$Http2Stream.toHttp2Headers
  2.7% io/micronaut/http/server/netty/AbstractNettyHttpRequest.decodeParameters
  2.0% io/micronaut/http/uri/UriTemplateMatcher.tryMatch
```

### tuned-h2c

```
# load
finished in 22.02s, 146298.18 req/s, 3.21MB/s
requests: 3218560 total, 3224960 started, 3218560 done, 3218560 succeeded, 0 failed, 0 errored, 0 timeout
status codes: 3218728 2xx, 0 3xx, 0 4xx, 0 5xx
req/s           :    2168.09     2404.39     2285.72      105.62    71.88%
# profile
total 4088
-- top self
 10.7% syscall
  4.6% io/netty/buffer/UnpooledSlicedByteBuf.unwrap
  4.0% io/netty/buffer/AdaptivePoolingAllocator$Magazine.newBuffer
  2.8% io/netty/util/Recycler$DefaultHandle.claim
  2.8% itable stub
  2.6% io/netty/channel/ChannelOutboundBuffer.forEachFlushedMessage
  2.3% io/netty/channel/uring/IoUringSocketChannel$IoUringSocketUnsafe$2.processMessage
  2.1% io/netty/channel/ChannelOutboundBuffer.removeBytes
-- top inclusive micronaut/entry/netty
 50.9% io/micronaut/http/server/netty/handler/Http2ServerHandler$ConnectionHandler.channelRead
 50.9% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 50.8% io/netty/handler/codec/ByteToMessageDecoder.callDecode
 50.8% io/netty/handler/codec/http2/DefaultHttp2FrameReader.readFrame
 50.8% io/netty/handler/codec/ByteToMessageDecoder.decodeRemovalReentryProtection
 50.8% io/netty/handler/codec/http2/Http2ConnectionHandler.decode
 50.8% io/netty/handler/codec/http2/DecoratingHttp2ConnectionDecoder.decodeFrame
 50.8% io/netty/handler/codec/http2/Http2ConnectionHandler$FrameDecoder.decode
 50.8% io/netty/handler/codec/http2/DefaultHttp2ConnectionDecoder.decodeFrame
 50.4% io/netty/handler/codec/http2/DefaultHttp2FrameReader.processPayloadState
-- leaf-most framework frame (self attributed)
 40.6% (no micronaut/entry frame)
 13.1% io/micronaut/http/server/netty/handler/Http2ServerHandler$Http2Stream.writeHeaders
  8.3% io/micronaut/http/server/netty/handler/Http2ServerHandler$ConnectionHandler.channelRead
  7.5% io/micronaut/http/server/netty/handler/Http2ServerHandler$ConnectionHandler.channelReadComplete
  4.9% io/micronaut/http/server/netty/handler/Http2ServerHandler.onHeadersRead
  4.1% io/micronaut/http/server/netty/handler/Http2ServerHandler$Http2Stream.toHttp2Headers
  2.4% io/micronaut/buffer/netty/NettyReadBufferFactory.copyOf
  1.7% io/micronaut/http/server/netty/AbstractNettyHttpRequest.decodeParameters
```

### tuned-json-comp

```
# load
    Latency   18.02ms   16.70ms   23.00ms   30.30ms   63.80ms
  299338 requests in 22.00s, 299303 responses
  Throughput: 13.60K req/s
  Latency samples: 299303 / 299303 responses (100.0%)
# profile
total 3945
-- top self
 21.7% deflate_slow
 18.3% longest_match
 13.0% syscall
  7.2% compress_block
  5.0% pqdownheap
  3.1% build_tree
  1.8% tools/jackson/core/json/UTF8JsonGenerator.writeName
  1.7% tools/jackson/core/json/UTF8JsonGenerator._writeStringSegment
-- top inclusive micronaut/entry/netty
 83.7% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 82.6% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 82.4% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 82.4% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 82.2% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 82.2% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 82.1% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 82.0% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 82.0% io/micronaut/core/propagation/ThreadContext.propagate
 81.8% io/micronaut/http/server/netty/RoutingInBoundHandler$$Lambda.0x000000002b935c00.run
-- leaf-most framework frame (self attributed)
 58.4% io/micronaut/http/server/netty/handler/Compressor$Session.push
 17.1% (no micronaut/entry frame)
  4.2% io/micronaut/serde/jackson/JacksonEncoder.encodeKey
  3.3% io/micronaut/serde/jackson/JacksonEncoder.encodeString
  2.4% io/micronaut/http/server/netty/handler/Compressor$Session.finish
  1.8% io/micronaut/http/server/netty/handler/Compressor.prepare
  1.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  1.6% io/micronaut/serde/jackson/JacksonEncoder.encodeLong
```

### tuned-json

```
# load
    Latency   6.59ms   5.92ms   9.36ms   13.40ms   28.80ms
  854439 requests in 22.00s, 853854 responses
  Throughput: 38.80K req/s
  Latency samples: 853854 / 853854 responses (100.0%)
# profile
total 4533
-- top self
 32.7% syscall
  6.8% tools/jackson/core/io/SerializedString.appendQuotedUTF8
  6.8% tools/jackson/core/json/UTF8JsonGenerator.writeName
  5.3% tools/jackson/core/json/UTF8JsonGenerator._writeStringSegment
  3.3% tools/jackson/core/json/JsonWriteContext.writeValue
  2.5% tools/jackson/core/json/UTF8JsonGenerator.writeString
  2.3% tools/jackson/core/json/UTF8JsonGenerator._verifyValueWrite
  2.3% io/micronaut/serde/jackson/JacksonEncoder.serializableKey
-- top inclusive micronaut/entry/netty
 61.7% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 60.2% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 59.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 59.5% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 59.4% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 59.3% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 59.0% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 59.0% io/micronaut/core/propagation/ThreadContext.propagate
 59.0% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 58.8% io/micronaut/http/server/netty/RoutingInBoundHandler$$Lambda.0x0000000063930000.run
-- leaf-most framework frame (self attributed)
 39.2% (no micronaut/entry frame)
 16.1% io/micronaut/serde/jackson/JacksonEncoder.encodeKey
 10.8% io/micronaut/serde/jackson/JacksonEncoder.encodeString
  7.2% io/micronaut/serde/jackson/JacksonEncoder.encodeLong
  2.3% io/micronaut/serde/jackson/JacksonEncoder.serializableKey
  2.1% io/micronaut/serde/jackson/JacksonJsonMapper.writeValue
  2.0% io/micronaut/serde/jackson/JacksonEncoder.encodeObject
  1.8% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
```

### tuned-pipelined

```
# load
    Latency   7.21ms   6.29ms   10.30ms   15.40ms   29.90ms
  12516064 requests in 22.00s, 12516064 responses
  Throughput: 568.81K req/s
  Latency samples: 12516064 / 12516064 responses (100.0%)
# profile
total 4265
-- top self
 24.8% syscall
  4.7% io/netty/util/internal/shaded/org/jctools/queues/varhandle/VarHandleQueueUtil.soRefElement
  2.2% java/lang/StringLatin1.charAt
  1.6% io/netty/handler/codec/DefaultHeaders.get
  1.2% java/lang/StringLatin1.indexOf
  1.1% java/lang/String.checkIndex
  1.1% [vdso]
  1.1% java/lang/invoke/VarHandleInts$FieldInstanceReadWrite.compareAndSet
-- top inclusive micronaut/entry/netty
 67.3% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 63.5% io/netty/handler/codec/ByteToMessageDecoder.callDecode
 56.4% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 55.5% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 55.4% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 53.7% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 53.5% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 50.9% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 50.7% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 50.6% io/micronaut/core/propagation/ThreadContext.propagate
-- leaf-most framework frame (self attributed)
 43.0% (no micronaut/entry frame)
 14.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  4.5% io/micronaut/http/netty/NettyHttpHeaders.validateHeader
  3.0% io/micronaut/http/uri/UriTemplateMatcher.tryMatch
  2.5% io/micronaut/buffer/netty/NettyReadBufferFactory.copyOf
  1.4% io/micronaut/http/server/util/PerSecondCache.now
  1.3% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
  1.2% io/micronaut/http/netty/NettyHttpHeaders.get
```

### tuned-static-br

```
# load
    Latency    20.59ms    7.53ms  85.10ms   72.20%
    Req/Sec     6.25k     0.99k    9.39k    69.09%
  273861 requests in 22.07s, 12.13GB read
Requests/sec:  12410.39
Transfer/sec:    562.96MB
# profile
total 4718
-- top self
 33.6% syscall
  6.8% ___pthread_cond_signal
  4.7% jint_disjoint_arraycopy_stub (stub gen)
  4.0% io/micronaut/http/body/stream/ExtendedInputStream.readSome
  3.1% __GI___futex_abstimed_wait_cancelable64
  2.7% __GI___read
  2.6% java/util/concurrent/ForkJoinPool.deactivate
  2.3% statx
-- top inclusive micronaut/entry/netty
 20.3% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 20.3% io/micronaut/core/propagation/ThreadContext.propagate
 19.7% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 18.8% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 18.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 18.6% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 18.5% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 18.5% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 18.4% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 18.4% io/micronaut/http/server/netty/NettyRequestLifecycle.handleNormal
-- leaf-most framework frame (self attributed)
 49.5% (no micronaut/entry frame)
 11.2% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  7.9% io/micronaut/scheduling/instrument/InstrumentedExecutor.execute
  4.6% io/micronaut/http/body/stream/ExtendedInputStream.readSome
  4.0% io/micronaut/http/server/body/SystemFileBodyWriter.write
  3.8% io/micronaut/http/server/body/SystemFileBodyWriter$RangeInputStream.read
  3.2% httparena/StaticController.file
  1.7% io/micronaut/http/body/stream/ExtendedInputStream$Wrapper.read
```

### tuned-static-plain

```
# load
    Latency    10.43ms    4.56ms  75.28ms   83.17%
    Req/Sec    12.49k     2.06k   17.61k    72.67%
  546725 requests in 22.08s, 4.30GB read
Requests/sec:  24758.95
Transfer/sec:    199.24MB
# profile
total 4637
-- top self
 25.2% syscall
  7.7% ___pthread_cond_signal
  4.8% __xstat64
  4.7% __GI___futex_abstimed_wait_cancelable64
  3.9% __GI___open64
  3.0% statx
  2.4% __GI___access
  2.1% java/util/concurrent/ForkJoinPool.deactivate
-- top inclusive micronaut/entry/netty
 38.7% io/micronaut/core/propagation/PropagatedContextImpl.propagate
 38.7% io/micronaut/core/propagation/ThreadContext.propagate
 38.6% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 37.2% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 36.9% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 36.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 36.6% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 36.5% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 36.3% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 36.0% io/micronaut/http/server/netty/RoutingInBoundHandler.lambda$handleNormal$0
-- leaf-most framework frame (self attributed)
 42.7% (no micronaut/entry frame)
  9.5% io/micronaut/http/server/body/SystemFileBodyWriter.write
  8.3% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  8.2% io/micronaut/scheduling/instrument/InstrumentedExecutor.execute
  4.4% httparena/StaticController.file
  3.3% io/micronaut/http/server/types/files/SystemFile.getLength
  3.1% io/micronaut/http/server/types/files/SystemFile.getLastModified
  2.1% io/micronaut/http/body/stream/ExtendedInputStream.readSome
```

### tuned-tls-h1

```
# load
    Latency     6.07ms   18.07ms 408.05ms   98.81%
    Req/Sec    27.83k     5.61k   37.98k    80.37%
  1199701 requests in 22.08s, 4.79GB read
Requests/sec:  54329.36
Transfer/sec:    222.07MB
# profile
total 4347
-- top self
 32.8% syscall
  4.4% tools/jackson/core/io/SerializedString.appendQuotedUTF8
  4.0% tools/jackson/core/json/UTF8JsonGenerator.writeName
  2.7% galoisCounterMode_AESCrypt_stub (stub gen)
  2.7% tools/jackson/core/json/UTF8JsonGenerator._writeStringSegment
  2.1% tools/jackson/core/json/JsonWriteContext.writeValue
  1.6% tools/jackson/core/json/UTF8JsonGenerator.writeString
  1.2% io/micronaut/serde/jackson/JacksonEncoder.serializableKey
-- top inclusive micronaut/entry/netty
 49.8% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 49.6% io/netty/handler/codec/ByteToMessageDecoder.callDecode
 49.5% io/netty/handler/codec/ByteToMessageDecoder.decodeRemovalReentryProtection
 41.2% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 41.0% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelRead
 41.0% io/micronaut/http/server/netty/handler/PipeliningServerHandler$MessageInboundHandler.read
 40.9% io/micronaut/http/server/netty/websocket/NettyServerWebSocketUpgradeHandler.accept
 40.9% io/micronaut/http/server/netty/RoutingInBoundHandler.accept
 40.3% io/micronaut/http/server/netty/RoutingInBoundHandler.handleNormal
 40.3% io/micronaut/core/propagation/ThreadContext.propagate
-- leaf-most framework frame (self attributed)
 47.5% (no micronaut/entry frame)
 11.3% io/micronaut/http/server/netty/handler/PipeliningServerHandler.channelReadComplete
  9.5% io/micronaut/serde/jackson/JacksonEncoder.encodeKey
  6.5% io/micronaut/serde/jackson/JacksonEncoder.encodeString
  4.6% io/micronaut/serde/jackson/JacksonEncoder.encodeLong
  1.7% io/micronaut/http/server/netty/handler/PipeliningServerHandler.write0
  1.4% io/micronaut/serde/support/serializers/StringIterableSerializer.serialize
  1.4% io/micronaut/serde/jackson/JacksonEncoder.encodeObject
```

### tuned-ws-echo

```
# load
    Latency   1.53ms   1.35ms   2.13ms   5.33ms   9.31ms
  Throughput: 166.59K req/s
  Latency samples: 3665860 / 3665860 responses (100.0%)
# profile
total 4742
-- top self
 70.4% syscall
  1.3% itable stub
  0.7% io/netty/channel/AbstractChannelHandlerContext.executor
  0.7% io/netty/channel/uring/IoUringIoHandler.handleFastPath
  0.6% java/lang/invoke/VarHandleReferences$FieldInstanceReadWrite.compareAndSet
  0.5% java/lang/invoke/VarHandleInts$FieldInstanceReadWrite.compareAndSet
  0.4% io/netty/channel/AbstractChannelHandlerContext.fireChannelReadComplete
  0.4% io/netty/channel/AbstractChannelHandlerContext.fireChannelRead
-- top inclusive micronaut/entry/netty
 18.7% io/netty/handler/codec/ByteToMessageDecoder.channelRead
 16.6% io/netty/handler/codec/ByteToMessageDecoder.fireChannelRead
 15.7% io/micronaut/http/server/netty/websocket/NettyServerWebSocketHandler.channelRead0
 15.7% io/micronaut/http/server/netty/websocket/NettyServerWebSocketHandler.routeFrameRead
 15.7% io/micronaut/http/netty/websocket/AbstractNettyWebSocketHandler.channelRead0
 15.7% io/micronaut/http/server/netty/websocket/NettyServerWebSocketHandler.handleWebSocketFrame
 15.7% io/micronaut/http/netty/websocket/AbstractNettyWebSocketHandler.handleWebSocketFrame
  9.3% io/micronaut/http/server/netty/websocket/NettyServerWebSocketHandler.invokeExecutable
  9.3% io/micronaut/core/propagation/PropagatedContextImpl.propagate
  9.2% io/micronaut/core/propagation/ThreadContext.propagate
-- leaf-most framework frame (self attributed)
 81.7% (no micronaut/entry frame)
  3.7% io/micronaut/http/netty/websocket/NettyWebSocketSession.sendAsync
  2.9% io/micronaut/http/bind/binders/DefaultUnmatchedRequestArgumentBinder.createSpecific
  0.8% io/micronaut/http/reactive/execution/ReactorExecutionFlowImpl.defuse
  0.6% io/micronaut/core/naming/NameUtils.capitalize
  0.6% httparena/EchoWebSocket.onMessage
  0.6% io/micronaut/http/netty/websocket/WebSocketMessageEncoder.encodeMessage
  0.5% io/micronaut/core/propagation/ThreadContext.remove
```
