#!/bin/sh
set -eu
cd "$(dirname "$0")"
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
PORT=${PORT:-19092}
RUN_ID=kafka-cep-$(date -u +%Y%m%dT%H%M%SZ)
RESULTS=$PWD/results/$RUN_ID
mkdir -p "$RESULTS"
./mvnw -B -ntp -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt > "$RESULTS/build.log" 2>&1
CP="target/test-classes:target/classes:$(cat target/classpath.txt)"
java -cp "$CP" dev.infrahack.fraud.BrokerCheck ports "$PORT"
CLUSTER_ID=$(kafka-storage random-uuid)
{
  echo "run_id: $RUN_ID"
  echo "provenance: measured"
  echo "cluster_id: $CLUSTER_ID"
  echo "runtime: Flink 2.3.0 / connector 5.1.0-2.3 RC1 / Java 21"
  echo "scope: correctness; one broker, one partition, parallelism 1; test heap 2 GiB"
  echo "opensearch: ${OPENSEARCH_ENDPOINT:-not exercised}"
  java -version 2>&1
  kafka-topics --version 2>/dev/null
} > "$RESULTS/manifest.txt"
rg --files --hidden -g '*.java' -g 'pom.xml' -g '*.json' -g '*.xml' -g '*.sh' -g 'mvnw*' -g '.mvn/**' \
  -g '!target/**' -g '!results/**' | LC_ALL=C sort | while IFS= read -r source_file; do
  shasum -a 256 "$source_file"
done > "$RESULTS/source-sha256.txt"
sh ./kafka-local.sh "$PWD/target/$RUN_ID" "$PORT" "$CLUSTER_ID" > "$RESULTS/broker.log" 2>&1 &
BROKER=$!
trap 'kill "$BROKER" 2>/dev/null || true; wait "$BROKER" 2>/dev/null || true' EXIT
READY=false
for attempt in $(seq 30); do
  kill -0 "$BROKER" 2>/dev/null || { tail -n 30 "$RESULTS/broker.log"; exit 1; }
  if java -cp "$CP" dev.infrahack.fraud.BrokerCheck owner "localhost:$PORT" "$CLUSTER_ID" >> "$RESULTS/ownership.log" 2>&1; then
    READY=true
    break
  fi
  sleep 1
done
test "$READY" = true
set -- "-Dkafka.bootstrap=localhost:$PORT" "-Devidence.dir=$RESULTS"
if test -n "${OPENSEARCH_ENDPOINT:-}"; then set -- "$@" "-Dopensearch.endpoint=$OPENSEARCH_ENDPOINT"; fi
if ./mvnw -B -ntp "$@" test > "$RESULTS/tests.log" 2>&1; then
  cp target/surefire-reports/TEST-*.xml "$RESULTS/"
  cat "$RESULTS/kafka-summary.json"
  echo "PASS: $RESULTS"
else
  tail -n 90 "$RESULTS/tests.log"
  exit 1
fi
