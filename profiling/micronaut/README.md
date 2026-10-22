# Micronaut profiling

Profiling of the `micronaut-tuned` and `micronaut-production` entries against the latest Micronaut 5.3.x snapshot, with async-profiler, over every path the entries serve.

## What was done

1. Published micronaut-core `5.3.x` to Maven Local (`./gradlew publishToMavenLocal`, JDK 25, with `nojavadoc.gradle` as an init script because javadoc errors otherwise stop the publish).
2. Built copies of both entries on JDK 27 against that snapshot. The only change to each `pom.xml` is importing `io.micronaut:micronaut-core-bom:5.3.0-SNAPSHOT` in `dependencyManagement`; the entries in `frameworks/` are untouched.
3. For each path, `run.sh` starts the entry pinned to CPUs 0-1, warms it up with the load generator on CPUs 2-3 for about 10 s, then profiles a 10 s window with async-profiler (`itimer`, 1 ms interval, collapsed output) while the load keeps running.
4. `analyze.py` summarises the collapsed stacks: top self frames, top inclusive Micronaut, entry and Netty-codec frames, and the leaf-most framework frame.
5. HTTP/3 was only checked functionally with `H3Client.java` (a small Netty HTTP/3 client), because `h2load-h3` could not be built.

Paths covered: baseline (GET, mixed GET and POST bodies, `/baseline2`), pipelined, json, json with gzip, async (`/delay`), static plain and precompressed, echo (10 KB body), TLS h1, h2 over TLS, h2c, WebSocket echo, async-db and fortunes. The production entry was run on the paths that matter for its differences from the tuned one.

The results and findings are in [REPORT.md](REPORT.md).

## Limits
- One run per path, not repeated. Absolute numbers are low (small shared machine); compare shapes, not values.
- async-db and fortunes are limited by Postgres sharing a CPU with the load generator.
- Postgres must be started with `max_connections=256` (the harness does); with the default 100, fortunes returns errors.

## Reproducing
Prerequisites: JDK 27, async-profiler 4.4, the two entries built against the snapshot, `wrk`, `gcannon`, `h2load`, Postgres 17 loaded with `data/pgdb-seed.sql`, and `/certs` and `/data` as the harness mounts them.

```
export JAVA_HOME=/path/to/jdk27 ASPROF=/path/to/asprof JARS=/path/to/built/entries OUT=/tmp/prof
export WRK=/path/to/wrk GCANNON=/path/to/gcannon
profiling/micronaut/all.sh tuned
profiling/micronaut/all.sh production
python3 -I profiling/micronaut/analyze.py $OUT/ap-tuned-json.collapsed
```
