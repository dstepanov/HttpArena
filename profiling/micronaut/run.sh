#!/bin/bash
# Profile one path of a Micronaut entry with async-profiler.
# usage: run.sh <entry: tuned|production> <name> <load command...>
# The load command runs pinned to CPUs 2-3 and the server to CPUs 0-1.
# Environment: JAVA_HOME (JDK 27), ASPROF (path to asprof), JARS (dir holding <entry>/target/micronaut-<entry>-httparena.jar), OUT (results dir)
set -u
: "${ASPROF:?path to asprof}" "${JARS:?dir with the built entries}" "${OUT:?output dir}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
e=$1; n=$2; shift 2
JAR=$JARS/$e/target/micronaut-$e-httparena.jar
FLAGS="-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints --enable-native-access=ALL-UNNAMED"
[ "$e" = tuned ] && FLAGS="$FLAGS -XX:+UseParallelGC -Dio.netty.buffer.checkBounds=false -Dio.netty.buffer.checkAccessible=false -Dio.netty.leakDetection.level=disabled"
mkdir -p "$OUT"
DATABASE_URL=${DATABASE_URL:-postgres://bench:bench@localhost:5432/benchmark} DATABASE_MAX_CONN=256 \
  taskset -c 0-1 java $FLAGS -jar "$JAR" > "$OUT/srv-$e-$n.log" 2>&1 &
P=$!
for i in $(seq 60); do curl -s -o /dev/null localhost:8080/pipeline && break; sleep 1; done
LOAD="taskset -c 2-3 $*"
eval "$LOAD" > /dev/null 2>&1 &   # warmup
W=$!; sleep 10; wait $W 2>/dev/null
eval "$LOAD" > "$OUT/load-$e-$n.txt" 2>&1 &
W=$!
sleep 3
"$ASPROF" -d 10 -e itimer -i 1ms -o collapsed -f "$OUT/ap-$e-$n.collapsed" $P > "$OUT/asprof-$e-$n.log" 2>&1
wait $W
kill $P; wait $P 2>/dev/null; sleep 1
echo "$e $n done"
