#!/bin/sh
# Isolated local node for the article benchmark; requires the matching installed k-NN plugin.
set -eu
cd "$(dirname "$0")"
: "${OPENSEARCH_DISTRIBUTION:=/opt/homebrew/opt/opensearch/libexec}"
: "${OPENSEARCH_EXECUTABLE:=/opt/homebrew/opt/opensearch/bin/opensearch}"
NODE_DIR=${NODE_DIR:-$PWD/target/article-node}
for port in 19200 19300 15005; do
  if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "Refusing occupied test port $port" >&2
    exit 1
  fi
done
mkdir -p "$NODE_DIR/config" "$NODE_DIR/logs" "$NODE_DIR/data"
sed "s|/opt/homebrew/var/log/opensearch|$NODE_DIR/logs|g;s|-XX:HeapDumpPath=data|-XX:HeapDumpPath=$NODE_DIR/data|g;s|-XX:ErrorFile=logs/|-XX:ErrorFile=$NODE_DIR/logs/|g" \
  "$OPENSEARCH_DISTRIBUTION/config/jvm.options" > "$NODE_DIR/config/jvm.options"
cp "$OPENSEARCH_DISTRIBUTION/config/log4j2.properties" "$NODE_DIR/config/"
cat > "$NODE_DIR/config/opensearch.yml" <<EOF
cluster.name: fraud-part2-isolated
node.name: fraud-part2-node
network.host: 127.0.0.1
http.port: 19200
transport.port: 19300
discovery.type: single-node
path.data: $NODE_DIR/data
path.logs: $NODE_DIR/logs
EOF
export OPENSEARCH_PATH_CONF="$NODE_DIR/config"
export OPENSEARCH_JAVA_OPTS='-Xms2g -Xmx2g -XX:ActiveProcessorCount=4 -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:15005'
exec "$OPENSEARCH_EXECUTABLE"
