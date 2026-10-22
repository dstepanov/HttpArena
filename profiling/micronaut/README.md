# Micronaut profiling

Profiles of `micronaut-tuned` and `micronaut-production` against a Micronaut 5.3.x snapshot. [REPORT.md](REPORT.md) and `results/load-*.txt` retain the original captures, including their errors and unmatched workloads. The corrected drivers produce new measurements; they do not recreate every historical command.

## Application source and build

The benchmark applications come from [HttpArena PR #1612](https://github.com/MDA2AV/HttpArena/pull/1612), not the `frameworks/` directories on this profiling branch. The original capture used the application before the rendering/JSON follow-up. For the corrected applications, pin commit `c311e88470064f8e7255d821a61d4e30e5a08854`:

```sh
git clone https://github.com/dstepanov/HttpArena.git httparena-apps
git -C httparena-apps checkout c311e88470064f8e7255d821a61d4e30e5a08854
mkdir -p /tmp/micronaut-profile-jars
cp -R httparena-apps/frameworks/micronaut-tuned /tmp/micronaut-profile-jars/tuned
cp -R httparena-apps/frameworks/micronaut-production /tmp/micronaut-profile-jars/production
```

The committed application POMs target platform 5.2.3. To profile the historical core snapshot, publish the clean micronaut-core commit `cc5ad2be35` to Maven Local with JDK 25 (`./gradlew -I /absolute/path/to/profiling/micronaut/nojavadoc.gradle publishToMavenLocal`), then add this dependency-management import to each **copied** application POM:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.micronaut</groupId>
      <artifactId>micronaut-core-bom</artifactId>
      <version>5.3.0-SNAPSHOT</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Build both copies with JDK 27 using `mvn clean verify`. Record the exact JDK build and resolved dependency versions with any new results. Dataset loading, PostgreSQL JSON tags and HTTP JSON responses use Micronaut Serialization; both applications exclude Jackson Databind. Jackson Core is still the streaming backend.

## Running profiles

Requires Linux with CPUs 0–3 available, JDK 27, async-profiler, `wrk`, `gcannon`, `h2load`, and a seeded PostgreSQL instance. Mount the repository's `data/dataset.json` and `data/static` under `/data`, and its server certificate/key under `/certs`, as the main harness does. Alternatively set the applications' `DATASET_PATH` and `STATIC_DIR` for the data locations. Start PostgreSQL with `max_connections=256` and load `data/pgdb-seed.sql`; keep its CPU placement separate from the load generator for database comparisons.

```sh
export JAVA_HOME=/path/to/jdk27 ASPROF=/path/to/asprof
export JARS=/tmp/micronaut-profile-jars OUT=/tmp/prof
export WRK=/path/to/wrk GCANNON=/path/to/gcannon
profiling/micronaut/all.sh tuned
profiling/micronaut/all.sh production
python3 -I profiling/micronaut/analyze.py "$OUT/ap-tuned-json.collapsed"
```

Each workload warms up for its full 22-second command, then runs again for measurement. The profiler records 10 seconds starting three seconds into that second run. Server, load and profiler failures stop the driver; its processes are cleaned up on failure or interruption. Use a fresh `OUT` directory for each repetition and repeat comparisons at least three times.

CPU sampling defaults to `cpu`. Where perf permissions prevent this, set `ASPROF_EVENT=ctimer` and record that limitation; it does not resolve kernel stacks. The historical `itimer` profiles cannot establish a syscall floor. Inclusive percentages overlap, and the analyzer's leaf-most-framework view includes work performed by that frame's callees.

Both entries now use the same five async-db row limits. Plain and Brotli static runs serve the same CSS asset; the HTTP/2 runs use the same stream concurrency, and HTTP/1 TLS uses their `/baseline2` endpoint. These changes require fresh results before protocol or compression comparisons.

For HTTP/3, compile `H3Client.java` against an application's shaded jar and run it with that jar on the classpath. `H3Client '/baseline2?a=13&b=42' 55` requires status 200, completion within the timeout and the exact body. A non-200 status, timeout, transport error or body mismatch exits unsuccessfully. The full harness HTTP/3 checks also require the `h2load-h3` image; a skipped check is not validation.

## Checks and measurements

```sh
python3 profiling/micronaut/test_runner.py
```

This exercises success, argument preservation, an occupied HTTP port, startup/readiness/load/profiler failures, empty or stale captures, interruption cleanup and failure propagation, without starting a server.

The [rendering microbenchmarks](benchmarks/README.md) and [saved JMH results](results/fortunes-followup-jmh.json) compare the before/after escaping and Rocker UTF-8 paths on the 200-row fixture. They measure isolated rendering work, not HTTP throughput. Static-file changes are deliberately deferred: entry-owned caches are disallowed, freshness must be preserved, and inline disk reads need cold-cache latency measurements.
