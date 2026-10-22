# Micronaut 5.3.x: full request call paths

Core revision `01f1ad23348b9a62a15b1372b712e4faae0bddfc`, application revision `c311e88470064f8e7255d821a61d4e30e5a08854`. Both applications resolve all 19 Micronaut core modules to this locally built `5.3.0-SNAPSHOT`. Platform 5.2.3 still supplies the ecosystem versions. Every JSON path uses Micronaut Serialization with Jackson Core; both Databind namespaces are excluded and absent from the shaded jars.

## Method and limits

Linux 7.0.14 on an ARM64 OrbStack VM, Temurin 27+35, async-profiler 4.6, PostgreSQL 18.6. Server vCPUs 0–1, clients 2–3, PostgreSQL 6–7. Fixed 512 MiB heap. Tuned uses io_uring and Parallel GC; production uses NIO and G1. This is a virtualized development host, not the HttpArena benchmark host. Comparisons between the two entries combine their configuration differences.

Sixteen workloads per entry: GET query, mixed GET/POST/chunked requests, HTTP/1 pipelining, JSON, gzip JSON, plain and Brotli versions of the same CSS asset, binary echo, asynchronous delay, five-limit database mix, 200-row Fortunes, WebSocket echo, HTTP/1 TLS, h2c, HTTP/2 TLS and HTTP/3. First path warms for 25 seconds, subsequent paths for 12 seconds. Unprofiled throughput is measured for 15 seconds; a separate 15-second load supplies the middle ten seconds of CPU/perf (1 ms), allocation (512 KiB), wall (20 ms) and monitor-contention (10 ms threshold) events in one JFR.

The 32 baseline captures contain 552,079 CPU samples. Maximum stack depth is 173 frames, below the configured 512-frame limit; no unwind error or truncation markers were found. Both server logs contain no warnings or errors. Some gcannon HTTP workloads report client read errors: retain those counts alongside throughput. Controlled patch comparisons use wrk and reject socket/HTTP errors. QUIC load also reports packet loss separately from HTTP failures.

Netty normally unlinks extracted native libraries. Retaining them with `-Dio.netty.native.deleteLibAfterLoading=false` lets the profiler read the existing ELF symbol table. Native TLS/QUIC follow-ups also use DWARF C-stack unwinding. These follow-ups must be used for native internal attribution; the initial QUIC capture resolves Java-to-JNI boundaries but groups much native work under the library filename.

Allocation profiles estimate bytes, CPU profiles count on-CPU samples and wall profiles include idle service/GC/client-manager threads. Inclusive percentages overlap. All percentages below identify the metric and scope; they are not summed as independent costs. The small recorded monitor wait does not rule out allocator CAS/spin overhead or native lock contention.

## Request paths and opportunities

### JSON

`socket → PipeliningServerHandler → RoutingInBoundHandler → request lifecycle/argument binding → BenchmarkController.json → ResponseLifecycle → JsonMessageHandler → JacksonJsonMapper → generated/introspection serializers → JacksonEncoder → UTF8JsonGenerator → ByteBufOutputStream → outbound protocol/transport`.

On tuned JSON, 63.3% of CPU samples lie in Serialization's call tree after excluding kernel and GC leaves. The largest leaves are `UTF8JsonGenerator._writeStringSegment` (12.7%), `writeName` (6.3%) and string character access (6.0%). `SerializedString.appendQuotedUTF8` copies already cached name bytes; it does not demonstrate repeated name encoding. Jackson Core here is the streaming backend for Micronaut Serialization, not Databind.

`BenchmarkController.json` accounts for 48.9% of estimated allocated bytes, with `PricedItem` alone at 45.4%. The tested prototype stores an immutable dataset `Item` plus the request-specific total, using Serialization's compile-time support for `@JsonUnwrapped` to retain the flat JSON shape. No response, partial JSON or computed total is cached. The prototype was rejected: its observed median HTTP throughput was 12.1% lower with substantial VM variation. Estimated allocation per response fell from about 7.1 kB to 6.1 kB (about 15%; the ten-second profile bytes are normalized by the surrounding 15-second load rate). The new profile puts 19.8% of leaf CPU samples in generated `PricedItem` introspection dispatch and adds boxed `Long` allocation at 13.1% of total bytes. This suggests that accessor/boxing overhead erases the benefit of the smaller object. The final application keeps its original flat record.

### Compression

The same request/serialization path continues through `Compressor.push → JdkZlibEncoder → native zlib`. `deflate_slow`, `longest_match`, `compress_block`, `pqdownheap` and `build_tree` dominate the gzip capture (over 60% combined leaf CPU). Framework route/context changes have limited headroom here. A compression-level experiment changes the CPU/size tradeoff and must report bytes as well as requests; precomputing dynamic JSON is not an acceptable shortcut.

### Static files

`StaticController.file → SystemFile → SystemFileBodyWriter.write → metadata/range checks → RangeInputStream → InputStreamByteBody.StreamPublisher on the IO executor → ExtendedInputStream.readSome → read-buffer adaptation → outbound transport`.

`ExtendedInputStream.readSome` accounts for 73.1% of allocated bytes on tuned plain CSS. It allocates an 8 KiB array per attempt, trims partial reads into another array and allocates again to discover EOF. The file is roughly 8 KiB, making these temporary buffers expensive relative to the payload. The CPU path also includes per-request metadata, open, close, executor handoff and copies.

A framework-native file-channel/read-buffer publisher could avoid intermediate arrays while preserving ranges, backpressure, cancellation and error cleanup. Keep disk IO off the event loop, test cold files and retain immediate file/twin replacement visibility. An application-owned persistent file cache would violate this benchmark's rules and freshness contract.

### Binary echo

`request ByteBody → body binder → NettyByteBuffer.toByteArray → controller byte[] → ByteArrayBodyWriter/ByteBodyFactory → NettyReadBufferFactory.copyOf → outbound transport`.

For the 10 KiB echo, inbound `toByteArray` is 12.9% inclusive CPU and the outbound buffer-copy path is another 7.0% inclusive, on distinct branches. A tuned endpoint forwarding an owned ByteBody is a candidate. Do not remove copies globally: publishers can reuse arrays, and byte-array MVC responses have ownership guarantees. A forwarding implementation needs chunked, large-body, cancellation, backpressure and size-limit/error-response tests.

### Query binding and small/pipelined responses

`QueryValueArgumentBinder → AbstractNettyHttpRequest.decodeParameters → Netty QueryStringDecoder → NettyHttpParameters` builds a decoded map and then copies it into another map. The wrapper constructor accounts for 10.6% of baseline allocated bytes. A decoder-owned-map path could remove the duplicate copy; the existing constructor's snapshot semantics and client mutation callback must stay intact.

Small text responses follow `CharSequenceBodyWriter → NettyReadBufferFactory.copyOf → ByteBufUtil.writeUtf8 → safeDirectWriteUtf8 → DirectByteBuffer.put`. The direct-buffer UTF-8 branch is 5.5% inclusive CPU on baseline and 13.2% on pipelining. This capture uses the safe JDK 27 path. Any encoder change needs real Unicode and end-to-end validation rather than disabling memory safety.

Adaptive allocation is a larger shared branch: 16.7% inclusive baseline CPU, 28.0% pipelining, 21.3% echo and 17.8% h2c. HTTP/2 header/data writes repeatedly allocate small buffers through `AdaptivePoolingAllocator → Magazine.tryAllocate → StampedLock.tryWriteLock`; lock acquisition CPU is not evidence of blocked-monitor time. A separate alternating pooled/adaptive experiment tests this setting without changing the application.

### Asynchronous work and PostgreSQL

The delay endpoint schedules completion on the current server event loop. Its CPU capture has only 2,400 samples in ten seconds, versus about 19,000–20,000 for saturated CPU paths. Idle/select/scheduler wall time is expected for the 10 ms delay, not a blocked request thread.

`route/query binding → Vert.x Pool.preparedQuery → PgEncoder → PostgreSQL → PgDecoder/DataTypeCodec → completion callback → Micronaut Serialization tag decode → ItemList → future completion → response serializer → server transport`.

Each request makes one SQL query; the five templates choose row limits, not query counts. `PgEncoder.flush` appears in 17.0% of tuned CPU stacks before the change and about 9.1% with the retained configuration. Tag decoding stays in Micronaut Serialization (`tags::text` bypasses the driver's JSONB codec); its mapper path accounts for 26.6% of allocated bytes, with parser/context creation, strings, lists and tag UTF-8 conversion underneath. Do not replace it with Databind or a hand-written JSON parser.

PostgreSQL used a median 199.4% CPU across its two assigned vCPUs during the production mixed DB measurement. Different row limits complete at different rates, so the two entries also produce different response mixes. Neither their raw mixed throughput difference nor client-side CPU alone identifies a Micronaut bottleneck. The statement-cache experiment holds the SQL and 20-row response size fixed. The prototype enables the driver's prepared-statement cache; every request still queries and decodes fresh rows.

### Fortunes

`Vert.x query/row decoding → add runtime fortune → sort → escape/render → UTF-8 byte response → server transport`.

Both entries use the same 200 database fortunes and add one runtime row. Tuned's remaining leaves include whole-page UTF-16-to-UTF-8 encoding (11.8% CPU) and escaping (6.6% self, 20.6% including callees). Production's largest application leaf is Rocker's HTML escaping (8.4%). The previously applied Rocker byte-output change already avoids a whole-page String round trip. A direct UTF-8 escaping writer is a further measured experiment, not a reason to bypass escaping or cache the rendered page.

### HTTP/2, TLS, WebSocket and QUIC

HTTP/2 runs use equal connection/stream concurrency across TLS and cleartext. Their stacks include HPACK, stream state/flow control, response header writes, buffer allocation and write scheduling. The HTTP/1 TLS test uses the same baseline route but a different concurrency shape; it is not a pure protocol-speed comparison.

WebSocket keeps Micronaut's upgrade, frame decode/encode and transport paths. Kernel leaves account for about 43.9% of tuned samples, while the application echo method itself is small. This is measured kernel CPU on this VM, not proof of an irreducible syscall floor.

Both HTTP/3 baseline loads completed over one million successful requests with zero HTTP failures/timeouts. QUIC packet loss is still recorded in the client logs. The native follow-up resolves `<octets::Octets>::get_u8` (2.1% leaf CPU), `<quiche::frame::Frame>::from_bytes` (1.7%), `<octets::Octets>::peek_u8` (1.2%) and `<quiche::Connection>::send_single` (1.0%) in the tuned capture. Packet/frame and byte decoding are visible costs alongside UDP and crypto. The initial library-sized native block did not justify blaming encryption alone. Use the native follow-up graphs for internal QUIC/crypto attribution.

### Context propagation

`PropagatedContextImpl.propagate` appears in 37.0% of tuned baseline CPU stacks because it surrounds downstream route/serialization/write work. These applications use thread-local propagation by default. The inclusive share is not the cost of propagation, and is not a basis for removing it or changing request-context semantics.

## Controlled experiments and final decisions

All comparisons use the same pinned 5.3 core, JVM, heap, GC, transport, fixtures, CPU placement and client settings within each experiment. Jars stay unchanged for the allocator experiment; only `io.netty.allocator.type` changes. The statement-cache/JSON experiment uses three fresh-JVM pairs in alternating order. Pipelining uses three allocator pairs; the other allocator paths use two pairs in reversed order. No paired comparison reported socket or HTTP errors. These are observed medians/ranges on one ARM VM, not statistical confidence intervals or leaderboard scores.

| Change / workload | Before median req/s | After median req/s | Observed delta | Decision |
|---|---:|---:|---:|---|
| Unwrapped JSON model / json | 129,483 | 113,805 | -12.1% | Reject JSON model |
| Prepared-statement cache / async-db | 23,750 | 38,421 | +61.8% | Keep in both entries |
| Prepared-statement cache / fortunes | 18,257 | 19,299 | +5.7% | No reliable gain claim |
| Pooled allocator / pipeline | 1,083,105 | 1,727,650 | +59.5% | Keep for tuned |
| Pooled allocator / baseline-get | 439,400 | 442,966 | +0.8% | Keep for tuned; GET is level |
| Pooled allocator / json | 137,728 | 153,233 | +11.3% | Keep for tuned |
| Pooled allocator / echo | 245,556 | 297,469 | +21.1% | Keep for tuned |
| Pooled allocator / h2c | 432,789 | 533,672 | +23.3% | Keep for tuned |

For fixed 20-row DB requests, the uncached runs were 21,199–25,230 req/s and cached runs 38,306–40,240 req/s. Median p99 was 12.45 ms versus 7.32 ms. This experiment changes no SQL or returned data: every request still executes its query and decodes JSON tags through Micronaut Serialization. Fortunes has overlapping ranges, so its median difference is not a reliable rendering or cache gain.

The JSON model's baseline varied substantially (85,487–144,215 req/s) versus 107,105–115,207 for the experiment. Its median did not improve; combined with the dispatch/boxing profile, that is sufficient to reject the extra model complexity rather than promote an allocation-only win.

Pooled-buffer process RSS observations overlapped: late-process snapshots were about 347–373 MiB for adaptive and 348–384 MiB for pooled. These coarse snapshots include the Java heap and are not an isolated native-memory measurement. Buffer retention still depends on threads and workload.

The retained application patch is deliberately small: enable `PgConnectOptions.setCachePreparedStatements(true)` in both entries and add `-Dio.netty.allocator.type=pooled` to the tuned JVM. JSON DTOs and rendering code remain as in the baseline. The profiling runner now keeps extracted native libraries for symbol resolution and follows the tuned allocator setting. The build instructions explicitly override `micronaut.core.version`; importing the snapshot BOM alone was insufficient because the platform parent manages core versions directly.

### Final configuration verification

Both applications pass `mvn clean verify` against their committed released platform and against the pinned 5.3 snapshot. All four shaded jars contain neither `tools.jackson.databind` nor `com.fasterxml.jackson.databind` classes. The four profiling-runner tests pass, including failure and interruption cleanup.

On Linux, both final 5.3 jars pass complete JSON/multiplier/gzip checks, database limits, fresh Unicode/quoted/tabbed/emoji tags after a committed update using the same query parameters, 0-byte and 10 KiB echo, chunked echo, 20 MiB echo, HTML escaping/row checks and immediate static-file replacement. Each entry also completes 10,000 requests over h2c, HTTP/2 TLS and HTTP/3 with zero failures/timeouts, and the independent QUIC client checks the exact sum body.

PR #1612 validation succeeded on application commit `56fa29320f4c75f23e905c6b35aed17a5592f3f7`. Each GitHub validation reports 111 passed, zero failed and one skipped; CI skips HTTP/3 because its h2load-h3 image is absent. The local real HTTP/3 checks above cover that gap for the 5.3 snapshot. The GitHub jobs validate the committed platform build; the local profiling and protocol checks above validate the pinned 5.3 build. No core source code or public API was changed.

## Remaining issues, ranked

1. **HTTP/2 teardown warning storm.** A separate 256-connection × 64-stream test on pinned 5.3 reproduces 10,432 closed-channel warnings with adaptive buffers and 12,288 with pooled buffers. All completed HTTP requests succeeded; each run still had exactly 16,384 started-but-unfinished requests when the duration-limited client stopped. The logs cluster around shutdown. This points to handling expected cancelled writes, not an allocator fix. `MultiplexedServerHandler` full-response paths use `ChannelHandlerContext.voidPromise()` and the JFR promise path uses `FIRE_EXCEPTION_ON_FAILURE`; streamed-batch failures already use `logStreamWriteFailure`. A framework fix should distinguish expected stream/channel cancellation from genuine write failures and preserve completion callbacks/JFR accounting, with focused close-during-write tests. Do not suppress all channel errors or lower the global log level.
2. **Static-file intermediate arrays.** Replace the file stream's byte-array staging with a read-buffer/channel path, preserving offload, freshness, ranges, cancellation and cold-file latency. This is the largest observed allocation opportunity for static files.
3. **Query-map duplication.** Add an owned-decoder-map path while preserving snapshot/mutation semantics for existing callers; test repeated parameters, ordering, escaping, limits and client URI updates.
4. **Unwrapped Serialization accessor cost.** Investigate primitive access and parent-property dispatch in the serializer/introspection path. Keep the simpler flat DTO until an end-to-end improvement is demonstrated.
5. **Echo ownership and UTF-8 output.** Evaluate native ByteBody forwarding and direct UTF-8 rendering only with contract tests; do not remove defensive copies globally.

### Client framing errors remain unassigned

The separate instrumented gcannon run attributes the read failures to its HTTP parser, which occasionally starts parsing a 4 KiB JSON body fragment as a response header. This occurs with both allocators (1,112 and 1,033 read failures in the short diagnostic runs). That establishes framing desynchronization, but does not by itself identify whether the cause is client receive bookkeeping, wire framing or the server. A wire-level reproduction is needed before assigning blame. No throughput claim in the controlled comparison depends on those gcannon JSON/DB runs; they use wrk with zero socket/HTTP errors. Pipelining's gcannon runs also had zero reported errors.

## Captures and reproduction

The accompanying capture bundle contains all JFRs, complete collapsed CPU/allocation/wall/monitor stacks, load/server/profiler logs, pinned dependency lists, jar hashes, experiment source and one-off capture/comparison/verification scripts. Open `graphs/index.html` in that bundle for the interactive graph index. Empty monitor captures are retained as empty files; they are not errors.

The scripts record the exact absolute container paths used in this session; update mount/helper paths when reproducing elsewhere. Keep the original baseline jars separate from rejected JSON prototypes and final accepted jars. Baseline uses adaptive allocation. Accepted tuned uses pooled allocation plus the statement cache; accepted production changes only the statement cache. Native follow-ups add retained ELF files and DWARF unwinding. The committed POMs continue to use platform 5.2.3; the copied profiling POMs override every core module to `5.3.0-SNAPSHOT` and were checked with `dependency:list`.

Do not infer a 5.2-to-5.3 release improvement from this work: there is no matched release comparison. Recheck observed throughput and buffer retention on the actual benchmark hardware.

## Baseline load measurements

Single unprofiled 15-second observations, separate from the repeated comparisons above. Some gcannon workloads reported read errors; see their raw logs. Protocol concurrency and payload sizes differ, so rows are not interchangeable speed tests.

| Workload | Tuned req/s | Production req/s |
|---|---:|---:|
| baseline-get | 446,732 | 334,487 |
| baseline-mix | 406,334 | 295,502 |
| pipeline | 1,129,236 | 839,365 |
| json | 117,106 | 103,182 |
| json-gzip | 34,444 | 33,487 |
| static-plain | 101,919 | 93,890 |
| static-br | 128,805 | 111,339 |
| echo | 148,840 | 290,080 |
| async | 9,947 | 9,611 |
| async-db | 17,801 | 25,610 |
| fortunes | 16,071 | 10,426 |
| ws | 409,165 | 421,189 |
| h1-tls | 268,836 | 223,023 |
| h2c | 297,206 | 409,539 |
| h2-tls | 397,917 | 423,668 |
| h3 | 71,053 | 118,959 |
