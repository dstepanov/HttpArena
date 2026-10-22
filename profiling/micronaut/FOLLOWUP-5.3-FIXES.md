# Micronaut 5.3: remaining issues and buffer improvements

This follows [the full call-path analysis](REPORT-5.3.md). Each remaining issue and buffer improvement received a separate agent investigation. Final core commit `6273f6b9bedfd18e6307ae466a1919894210053b`; final application commit `f000cc0ccc02da0957e958f7d9990743afe44bfe`. The integration branch starts at core `01f1ad23348b9a62a15b1372b712e4faae0bddfc` (`5.3.0-SNAPSHOT`); application comparisons start at `56fa29320f4c75f23e905c6b35aed17a5592f3f7`, including the previously retained statement cache and pooled tuned allocator. The user's existing core checkout was preserved.

## Fixes and decisions

| Area | Result |
| --- | --- |
| HTTP/2 teardown | Handle failed full-response, header, compressed-data, continue and final/JFR write promises. Ignore closed-channel completion at the promise. Add the missing TLS/H2 terminal error adapter after initial customizers, reusing the existing h2c/server policy. Preserve unexpected errors, observers and completion/JFR accounting. |
| Static files | Read file channels directly into existing native read buffers. Preserve IO-executor offload, demand, ranges, cancellation, errors, overridden stream behavior and file freshness. Ordinary streams retain their array path. |
| Query parameters | Take ownership of a fresh decoder map instead of copying it. Preserve the existing constructor's snapshot behavior, immutable list views, ordering, escaping, mutation and client URI callbacks. |
| UTF-8 buffers | Release the allocated native buffer if an arbitrary CharSequence throws during encoding. Keep the encoder and successful-byte behavior. |
| Client framing | Fix gcannon's provided-buffer-ring return bookkeeping: advancing both the ring tail and a relative offset overwrote an unconsumed descriptor. Independent-server and Micronaut tests establish the client fault. |
| Legacy JSON entry | Replace its remaining Databind mapper with Micronaut Serialization, add Serde metadata/processing and a complete JSON regression, and run that regression during Docker builds. |
| Echo | Keep native forwarding in both entries: large allocation reduction, level/overlapping throughput ranges, ownership/limits/reset regressions pass. |
| Fortunes | Keep direct UTF-8 in tuned: 75.2% less rendering allocation and about 10% more HTTP throughput in each of three pairs. |
| Serialization primitives | Narrow customized-serializer patch reduces boxing allocation; full affected checks pass. Generated and runtime flat controls retain their existing serializer paths. The flat application DTO remains unchanged. |

Core changes are provided as a patch against the pinned revision. They are distinct from the application PR, whose committed POMs retain released versions. Profiling copies override both `micronaut.core.version` and the core BOM; importing only the BOM does not override the parent-managed modules.

## Measured component results

Linux ARM64, Temurin 27+35, OrbStack VM, CPU affinity within the VM. Other host work continued, so CPU affinity does not provide physical-core isolation. Allocation counts are the strongest evidence here; isolated rendering/decoding timings do not establish HTTP throughput gains.

| Component | Before B/op | After B/op | Change |
| --- | ---: | ---: | ---: |
| Native 8 KiB file body | 16,800 | 872 | -94.8% |
| Native 1 MiB file body | 1,061,255 | 24,764 | -97.7% |
| Empty query | 120 | 120 | level |
| Two-key query | 744 | 528 | -29.0% |
| Repeated/escaped eight-key query | 2,144 | 1,720 | -19.8% |
| Tuned fortunes, 201 rows | 245,233 | 60,712 | -75.2% |

The static benchmark opens/closes a real file per operation, uses warm OS page cache and measures the body publisher with pooled Netty buffers. Two forks per variant run in both orders. A final compatibility check leaves ordinary stream allocation unchanged (about 1,061,156 B/op); the non-Netty heap factory adds about 1.1% allocation with level CPU time. No cold-disk speed claim follows from these results.

Query results use three forks per case, 3 x 1 s warmup and 5 x 1 s measurement, with the GC profiler. Empty queries retain a mutable parameter wrapper without mutating Netty's immutable empty map.

Fortunes results use the exact 200 database messages plus the runtime row, sorted before the rendering-only benchmark. All renderers produce the same 24,631 bytes. Three forks, 5 x 1 s warmup and 10 x 1 s measurement: previous StringBuilder 47.086 us/op, direct UTF-8 47.850 us/op, JDK writer 50.639 us/op. The JDK writer allocates 219,208 B/op. The direct renderer normalizes malformed UTF-16 during the existing escaping scan to preserve the previous JDK output, retains the byte-array response contract and releases temporary storage in finally. Real requests still query, construct, sort and escape fresh fortunes.

## Encoding and caching limits

The 27-cell, three-fork UTF-8 matrix covers tiny/large ASCII, Unicode and mutable CharSequences. The actual two-character response uses 56.9 ns and 188 B with Netty, 59.6 ns and 204 B with JDK getBytes plus bulk copy, and 72.5 ns and 244 B with JDK encodeString. Larger strings favor bulk encoding but allocate another array: 4 KiB ASCII is 310.5 ns/4,300 B versus Netty's 1,259.7 ns/188 B. No general encoder switch was retained. JDK and Netty also differ on some malformed surrogate sequences; this work preserves existing core encoding behavior.

No response, rendered page, database row, total or file-content cache was added. Prepared-statement caching reuses driver query metadata while every request queries fresh data. Existing cached JSON field-name bytes already avoid repeated name encoding. Factory-object allocation is below 1% in the relevant profiles, without a matching CPU hotspot; an extra factory cache was not justified.

The separate Serialization patch starts at `f70429359` on `3.2.x` (the version-only commit immediately after 3.2.5) and runs against core 5.3. It stores one primitive-kind byte per property when constructing a customized serializer, then reuses the existing direct primitive accessor. Formatting, filters, views, references, inclusion overrides, unwrapped parent access and custom serializers retain the original path. No property values are cached; SimpleObjectSerializer and SerBean are byte-identical to the baseline.

Three-fork controls, 5 x 1 s warmup and measurement, identical ALWAYS inclusion and byte-equal flat/unwrapped output:

| Serialization case | Baseline ops/s | Patched ops/s | Baseline B/op | Patched B/op |
| --- | ---: | ---: | ---: | ---: |
| Generated flat control | 102,009 | 98,795 | 14,818 | 14,893 |
| Unwrapped customized | 76,954 | 74,740 | 13,045 | 12,298 |
| Runtime flat control | 82,058 | 82,470 | 15,944 | 15,976 |

The unwrapped case allocates 5.7% less. Its timing shift tracks the unchanged generated-flat control; no speed claim is made. The application keeps its original flat DTO and released Serialization dependency. The earlier broad prototype was rejected after a runtime-flat regression and is not this patch. Both measured classpaths exclude Databind and Blackbird; the benchmark explicitly uses Micronaut Serialization's JacksonJsonMapper with Jackson Core.

## Error and ownership checks

The original high-concurrency HTTP/2 teardown reproduction emits 8,448 closed-channel warnings. The patched normal TLS and cleartext/static cases complete without closed-channel warnings or HTTP failures; bounded gzip completes 10,000 successful requests. The first promise-only fix still emitted native broken-pipe warnings during forced TLS aborts: TLS/H2 lacked the terminal error adapter already used by h2c. The final shared-policy bridge fixes that gap. Repeating both gzip/TLS and cleartext forced aborts now produces zero WARN, ERROR or ClosedChannelException messages, and the server remains responsive. Custom observers still see failures; expected socket closure uses the existing DEBUG policy, while genuine IO/non-IO failures remain ERROR. Real-TLS tests cover current and legacy handler modes and customizer ordering. No regex or global logging level changed. Completion and JFR accounting retain their final-write promises.

Echo tests cover empty, fixed/chunked, 20 MiB and 32 MiB bodies; independent request/buffer caps produce 413 before success headers. Native storage identity and cancellation/error refcounts are checked. Real TCP resets during an upload and after response writing starts release every observed buffer in both applications on released core and 5.3, including Linux controls.

An immediate full write followed by SO_LINGER(0) reset sometimes leaves input pending in this environment. A dedicated investigation reproduced it with plain Netty in manual and automatic read modes, then with a plain blocking JDK ServerSocket. Micronaut's demand and readPending state were correct. That evidence does not justify changing framework backpressure, adding socket polling or force-releasing a still-active body. The deterministic observed-close cases pass.

The gcannon patch passes a 131,072-return ring/wrap regression, 580 parser fragmentation checks, ASan/UBSan and the existing integration suite. The old binary reports errors against an independent server; the fixed binary reports zero over 9,066,274 responses. On Micronaut JSON, the original reports 606 errors and the fixed version reports zero over 1,549,372 responses. These correctness comparisons do not claim a speed improvement.

## Final HTTP comparison

Three alternating fresh-JVM pairs, 512 MiB heap, Parallel GC and pooled allocator, server CPUs 0-1/client 2-3/PostgreSQL 6-7. First route warms for 25 seconds, subsequent routes for 10 seconds; each unprofiled measurement lasts 15 seconds. Separate first-pair loads supply 10-second CPU/perf, allocation, wall and monitor captures. Core timing jar is `304e882877`; the subsequent TLS/H2 error-adapter fix changes no measured HTTP/1 path.

| Workload | Before median req/s (range) | After median req/s (range) | Median delta | Interpretation |
| --- | ---: | ---: | ---: | --- |
| Query | 539,810 (537,707-624,328) | 542,496 (459,997-568,411) | +0.5% | level; no speed claim |
| Static | 31,631 (28,146-31,639) | 31,109 (30,771-31,333) | -1.7% | level/overlapping; allocation improvement |
| Echo | 315,152 (292,807-341,569) | 332,539 (320,469-339,897) | +5.5% | overlapping; no reliable speed claim |
| Fortunes | 20,047 (18,331-20,246) | 22,030 (20,245-22,701) | +9.9% | all three pairs +9.9% to +12.1% |
| Unchanged JSON | 154,489 (151,211-161,328) | 156,958 (135,092-160,506) | +1.6% | level; control varies too |

All 30 measurements, warmups and profile loads report zero socket/HTTP errors; all six server logs contain no WARN/ERROR. All 45 embedded dependency pom.properties files match between timing jars. These are development-VM observations, not statistical confidence intervals or benchmark-host scores.

The ten new full captures contain 189,195 CPU samples; maximum recorded depth is 117, below 512, with no unwind/truncation markers. Sampled full-server allocation estimates (10-second bytes normalized by surrounding 15-second profiled-load rate) fall about 66% for static, 76% for echo and 51% for fortunes. Those are estimates; the component tables provide exact JMH B/op. Query's small whole-request estimate is noisy, so only its exact decoder B/op improvement is claimed.

The echo copy branches disappear. Gathering work rises because the forwarded duplicate can wrap a composite buffer, but the removed copies are larger in the captured CPU tree. No consolidation was added: the duplicate shares the request's underlying composite, and a separate contiguous copy has no demonstrated benefit. Fortunes removes whole-page String/getBytes work but moves encoding into escaping; isolated render time stays level, while less garbage benefits the complete request.

## Final integration validation

All three final 5.3 Maven builds pass (legacy 1 test, tuned 11, production 7), including exact JSON, escaping, large/chunked bodies, independent request/buffer limits, cancellation and real TCP resets. All three final 5.3 jars contain neither Databind namespace nor Micronaut's Databind adapter. Released-POM integration tests also pass; legacy passes with Docker's JDK 25. [Application CI](https://github.com/MDA2AV/HttpArena/actions/runs/39784832942) passes all three entries: legacy 57 passed/zero failed, tuned and production each 111 passed/zero failed/one HTTP/3 skip because the CI load-generator image is absent.

Both Linux entries pass complete JSON/gzip/multiplier bodies, DB limits and fresh Unicode tags after a committed update using identical parameters, empty/10 KiB/chunked/20 MiB echo, fortunes sorting/escaping and immediate file replacement. A new owned 1 MiB static file passes a page-cache-eviction hint, byte/suffix ranges, HEAD and atomic replacement. The hint is not a controlled physical cold-disk latency experiment. Each entry completes 10,000 h2c and 10,000 TLS HTTP/2 requests with zero failures/timeouts; the QUIC client verifies status 200 and the exact body. The earlier 10,000-request HTTP/3 stress belongs to the original core checkpoint; this new final integration repeats the exact-body QUIC check.

The final TLS/H2 adapter passes 42 focused cases and the native abort proof above. New benchmark packages are NullMarked; JMH compilation, benchmark Checkstyle and Spotless pass. The Serialization patch completes both affected module checks, aggregate Checkstyle, Spotless and docs: Gradle/HTML report 2,125 cases (2,110 passed, 15 skipped, zero failures). The complete Jackson suite required a validation-only 2 GiB test heap and fresh workers every 25 classes after the default worker ran out of heap. Existing tolerated Checkstyle/Javadoc diagnostics remain; no production build settings changed. XML counts one fewer existing JsonInclude parameter row than Gradle/HTML.

The final six affected core modules report 7,139 XML cases: 7,052 passed, 86 skipped and one failed. The complete server-netty suite has zero failures (2,556 cases, 22 skips). The sole client failure is `AsyncStreamingDecompressedLimitTest.decompressedEventStreamLargerThanMaxContentLengthFails`: it expects an ExecutionException but none is thrown. A pristine full-suite run at `01f1ad2334` reproduces that exact failure; isolated baseline and patched runs both pass. The initial candidate run also had a MaxRequestSizeSpec leak-scope teardown failure after its enabled tests passed; the final full server retry passes it. The pristine full server run has a separate leak-scope failure in AsyncRequestBodyFilterReadTest. These observations are retained rather than equating the different teardown failures or claiming an entirely green core check.

Root-wide check/docs/compatibility outcomes are being finalized. All six affected compatibility tasks passed in the earlier aggregate run; final source has no further public API change. Normal root configuration excludes Python CI tests unless `-Ppython-ci` is supplied.


## Source patches and saved results

- [Micronaut core 5.3 patch](patches/micronaut-core-5.3.patch), based on `01f1ad23348b9a62a15b1372b712e4faae0bddfc`; also preserved locally on `codex/53-callpaths`.
- [Application follow-up patch](patches/httparena-followup.patch), also on [PR #1612](https://github.com/MDA2AV/HttpArena/pull/1612).
- [Serialization primitive patch](patches/micronaut-serialization.patch), based on `f70429359` on Serialization `3.2.x`; it is separate from the released application dependency.
- [gcannon buffer-ring patch](patches/gcannon-ring.patch), based on `11c802b`.
- [Raw HTTP repetitions](results/fixes-5.3/http-runs.json), [summary](results/fixes-5.3/http-summary.json), [stack validation](results/fixes-5.3/stack-validation.json), and [component result files](results/fixes-5.3/).
- [Affected core test counts](results/fixes-5.3/core-affected-tests.json), [pristine baseline comparison](results/fixes-5.3/core-baseline-tests.json), [application CI](results/fixes-5.3/application-ci.json), [final protocol checks](results/fixes-5.3/final-protocol-verification.json), and [final jar hashes/Databind scan](results/fixes-5.3/final-jars.json).

The local deliverable includes the ten JFRs, complete collapsed CPU/allocation/wall/monitor stacks, all load/server/profiler logs, final jar hashes, verification evidence, runner scripts and interactive graphs at `graphs/index.html`. The earlier 45-capture bundle remains separate. No 5.2-to-5.3 release-speed claim is made; remeasure on the benchmark hardware.
