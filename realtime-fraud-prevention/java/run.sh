#!/bin/sh
set -eu
cd "$(dirname "$0")"
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
RUN_ID=cep-$(date -u +%Y%m%dT%H%M%SZ)
RESULTS="$PWD/results/$RUN_ID"
mkdir -p "$RESULTS"
{
  echo "run_id: $RUN_ID"
  echo "provenance: measured"
  echo "seed: 20261026"
  echo "runtime: Flink 2.3.0, streaming, local MiniCluster, parallelism 1"
  echo "scope: correctness only; test JVM -Xmx2g; no Kafka, OpenSearch or load test"
  java -version 2>&1
  ./mvnw -v
  uname -a
} > "$RESULTS/manifest.txt"
rg --files --hidden -g '*.java' -g 'pom.xml' -g '*.json' -g '*.xml' -g '*.sh' -g 'mvnw*' -g '.mvn/**' \
  -g '!target/**' -g '!results/**' | LC_ALL=C sort | while IFS= read -r source_file; do
  shasum -a 256 "$source_file"
done > "$RESULTS/source-sha256.txt"
if ./mvnw -B -ntp -Devidence.dir="$RESULTS" -Dmdep.outputFile=target/classpath.txt verify dependency:build-classpath > "$RESULTS/tests.log" 2>&1; then
  cp target/surefire-reports/TEST-*.xml "$RESULTS/"
  java -Xmx2g -Dlog4j.configurationFile=src/test/resources/log4j2-test.xml \
    -cp "target/classes:$(cat target/classpath.txt)" dev.infrahack.fraud.PaymentCepJob \
    "$RESULTS/ordered-input.jsonl" 2026-10-26T14:50:00Z cep-cli-v1 > "$RESULTS/cli.log" 2>&1
  test "$(rg -c '^case>' "$RESULTS/cli.log")" = 1
  rg -q '"behavior_vector"' "$RESULTS/cli.log"
  echo "PASS: $RESULTS"
  tail -n 12 "$RESULTS/tests.log"
else
  tail -n 70 "$RESULTS/tests.log"
  exit 1
fi
