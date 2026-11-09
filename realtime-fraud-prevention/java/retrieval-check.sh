#!/bin/sh
set -eu
cd "$(dirname "$0")"
: "${JAVA_HOME:=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
export JAVA_HOME
export PATH="$JAVA_HOME/bin:$PATH"
RUN_ID=${RUN_ID:-retrieval-$(date -u +%Y%m%dT%H%M%SZ)}
RESULTS=$PWD/results/$RUN_ID
mkdir -p "$RESULTS"
./mvnw -B -ntp -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt > "$RESULTS/build.log" 2>&1
rg --files src -g '*.java' -g '*.json' | LC_ALL=C sort | while IFS= read -r source_file; do
  shasum -a 256 "$source_file"
done > "$RESULTS/source-sha256.txt"
shasum -a 256 pom.xml retrieval-check.sh >> "$RESULTS/source-sha256.txt"
CP="target/test-classes:target/classes:$(cat target/classpath.txt)"
exec java --add-modules jdk.jdi -Xmx2g -cp "$CP" dev.infrahack.fraud.RetrievalBenchmark "$RESULTS" "$@"
