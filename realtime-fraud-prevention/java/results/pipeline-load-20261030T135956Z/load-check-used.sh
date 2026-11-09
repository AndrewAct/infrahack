#!/bin/sh
set -eu
cd "$(dirname "$0")"
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
RUN_ID=${RUN_ID:-pipeline-load-$(date -u +%Y%m%dT%H%M%SZ)}
LOAD_HEAP=${LOAD_HEAP:-4g}
RESULTS=$PWD/results/$RUN_ID
mkdir -p "$RESULTS"
./mvnw -B -ntp -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt > "$RESULTS/build.log" 2>&1
CP="target/test-classes:target/classes:$(cat target/classpath.txt)"
RETRIEVAL_RESULTS=${RETRIEVAL_RESULTS:-${1:?Pass the completed retrieval results directory}}
CLUSTER_ID=$(kafka-storage random-uuid)
export KAFKA_HEAP_OPTS='-Xms1g -Xmx1g'
sh kafka-local.sh "$PWD/target/$RUN_ID" 19092 "$CLUSTER_ID" > "$RESULTS/broker.log" 2>&1 &
BROKER=$!
LOAD_PID=
SAMPLER=
cleanup() {
  if test -n "$SAMPLER"; then kill "$SAMPLER" 2>/dev/null || true; fi
  kill "$BROKER" 2>/dev/null || true
  wait "$BROKER" 2>/dev/null || true
  curl -fsS -X DELETE "http://localhost:19200/fraud-load-$RUN_ID" > /dev/null 2>&1 || true
  curl -fsS -X DELETE "http://localhost:19200/fraud-load-$RUN_ID-history" > /dev/null 2>&1 || true
}
trap cleanup EXIT
for attempt in $(seq 30); do
  if java -cp "$CP" dev.infrahack.fraud.BrokerCheck owner localhost:19092 "$CLUSTER_ID" > "$RESULTS/ownership.log" 2>&1; then break; fi
  sleep 1
done
java -cp "$CP" dev.infrahack.fraud.BrokerCheck owner localhost:19092 "$CLUSTER_ID"
rg --files src -g '*.java' -g '*.json' | LC_ALL=C sort | while IFS= read -r source_file; do
  shasum -a 256 "$source_file"
done > "$RESULTS/source-sha256.txt"
shasum -a 256 pom.xml load-check.sh >> "$RESULTS/source-sha256.txt"
{
  java -version 2>&1
  sysctl -n hw.memsize hw.ncpu machdep.cpu.brand_string
  sysctl vm.swapusage
  echo "kafka_cluster_id=$CLUSTER_ID broker_pid=$BROKER"
  echo "application_heap_max=$LOAD_HEAP"
} > "$RESULTS/environment.txt"
java -Xmx"$LOAD_HEAP" -XX:+ExitOnOutOfMemoryError --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED \
  -cp "$CP" dev.infrahack.fraud.PipelineLoadBenchmark localhost:19092 "$RESULTS" "$RETRIEVAL_RESULTS" > "$RESULTS/load.log" 2>&1 &
LOAD_PID=$!
OS_PID=$(lsof -t -iTCP:19200 -sTCP:LISTEN)
{
  while kill -0 "$LOAD_PID" 2>/dev/null; do
    date -u +%s
    ps -p "$LOAD_PID,$BROKER,$OS_PID" -o pid=,rss=,pcpu=,time=
    sleep 1
  done
} > "$RESULTS/process-samples.txt" &
SAMPLER=$!
sleep 10
for test_pid in "$LOAD_PID" "$BROKER" "$OS_PID"; do
  jcmd "$test_pid" GC.heap_info > "$RESULTS/heap-$test_pid.txt" 2>&1 || true
done
if wait "$LOAD_PID"; then
  sysctl vm.swapusage >> "$RESULTS/environment.txt"
  cat "$RESULTS/load-summary.json"
else
  tail -n 50 "$RESULTS/load.log"
  exit 1
fi
