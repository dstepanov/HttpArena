# Fortunes rendering comparison

These JMH benchmarks retain the original and bulk-append escaping implementations, and compare Rocker's original String-to-UTF-8 round trip with direct byte output. Setup checks require byte-identical output. The fixture is the 200 seeded database messages plus the runtime row; fetching, sorting and decoding the fixture are outside the measured operations.

Build the corrected **production** application with JDK 27, and export the seeded messages:

```sh
psql "$DATABASE_URL" -At -c 'SELECT message FROM fortune ORDER BY id' > /tmp/fortunes-messages.txt
APP_JAR=/absolute/path/to/micronaut-production/target/micronaut-production-httparena.jar
mvn -f profiling/micronaut/benchmarks/pom.xml -Dapplication.jar="$APP_JAR" package
java -cp "profiling/micronaut/benchmarks/target/profiling-benchmarks-1.jar:$APP_JAR" \
  org.openjdk.jmh.Main 'httparena.*Benchmark.*' -foe true -prof gc \
  -jvmArgsAppend '-Xms512m -Xmx512m -XX:ActiveProcessorCount=2 -Dfortune.messages=/tmp/fortunes-messages.txt' \
  -rf json -rff /tmp/fortunes-jmh.json
```

JMH uses three forks, three one-second warmup iterations and five one-second measurements per fork. `-foe true` makes a failed fork fail the run. The application jar must be on the runtime classpath as well as the compile classpath.

The saved run used OpenJDK 27-ea+25 on macOS 26.7.1 arm64, one benchmark thread, G1, a 512 MB heap and `ActiveProcessorCount=2` (not CPU affinity). No endpoint throughput gain is inferred from these measurements. The Rocker baseline models the UTF-8 output conversion with `String.getBytes`, not Netty's exact response writer.
