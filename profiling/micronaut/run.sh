#!/bin/bash
# Profile one path of a Micronaut entry with async-profiler.
# usage: run.sh <entry: tuned|production> <name> <load command...>
# The load command runs pinned to CPUs 2-3 and the server to CPUs 0-1.
# Environment: JAVA_HOME (JDK 27), ASPROF (path to asprof), JARS (dir holding <entry>/target/micronaut-<entry>-httparena.jar), OUT (results dir)
set -euo pipefail
if [ "$#" -lt 3 ]; then
  echo "usage: $0 <tuned|production> <name> <load command...>" >&2
  exit 1
fi
: "${ASPROF:?path to asprof}" "${JARS:?dir with the built entries}" "${OUT:?output dir}"
export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$PATH"
e=$1; n=$2; shift 2
case "$e" in tuned|production) ;; *) echo "unknown entry: $e" >&2; exit 1 ;; esac
JAR=$JARS/$e/target/micronaut-$e-httparena.jar
[ -f "$JAR" ] || { echo "missing jar: $JAR" >&2; exit 1; }
for tool in java taskset curl "$ASPROF" "$1"; do
  command -v "$tool" >/dev/null || { echo "missing executable: $tool" >&2; exit 1; }
done
FLAGS="-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints --enable-native-access=ALL-UNNAMED -Dio.netty.native.deleteLibAfterLoading=false"
[ "$e" = tuned ] && FLAGS="$FLAGS -XX:+UseParallelGC -Dio.netty.allocator.type=pooled -Dio.netty.buffer.checkBounds=false -Dio.netty.buffer.checkAccessible=false -Dio.netty.leakDetection.level=disabled"
mkdir -p "$OUT"
P= W= A=
cleanup() {
  for pid in "$A" "$W" "$P"; do
    if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  done
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if curl --silent --max-time 1 -o /dev/null localhost:8080/pipeline; then
  echo "port 8080 already answers HTTP; stop that server before profiling" >&2
  exit 1
fi
DATABASE_URL=${DATABASE_URL:-postgres://bench:bench@localhost:5432/benchmark} DATABASE_MAX_CONN=256 \
  taskset -c 0-1 java $FLAGS -jar "$JAR" > "$OUT/srv-$e-$n.log" 2>&1 &
P=$!
ready=false
for i in $(seq 60); do
  kill -0 "$P" 2>/dev/null || { echo "server exited; see $OUT/srv-$e-$n.log" >&2; exit 1; }
  if curl --fail --silent --max-time 1 -o /dev/null localhost:8080/pipeline; then ready=true; break; fi
  sleep 1
done
$ready || { echo "server did not become ready" >&2; exit 1; }
taskset -c 2-3 "$@" > "$OUT/warmup-$e-$n.log" 2>&1 &
W=$!
wait "$W"
W=
taskset -c 2-3 "$@" > "$OUT/load-$e-$n.txt" 2>&1 &
W=$!
sleep 3
kill -0 "$W" 2>/dev/null || { echo "load exited before profiling" >&2; exit 1; }
# A previous capture must not make an empty profiler result look successful.
: > "$OUT/ap-$e-$n.collapsed"
"$ASPROF" -d 10 -e "${ASPROF_EVENT:-cpu}" -i 1ms -o collapsed -f "$OUT/ap-$e-$n.collapsed" "$P" > "$OUT/asprof-$e-$n.log" 2>&1 &
A=$!
wait "$A"
A=
wait "$W"
W=
kill -0 "$P" 2>/dev/null || { echo "server exited during the load" >&2; exit 1; }
[ -s "$OUT/ap-$e-$n.collapsed" ] || { echo "profiler produced no samples" >&2; exit 1; }
echo "$e $n done"
