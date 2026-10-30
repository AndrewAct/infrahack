#!/bin/sh
# Reused isolated KRaft setup from the connector gate; never uses Homebrew's data directory.
set -eu
DIR=$1
PORT=$2
CLUSTER_ID=$3
CONTROLLER_PORT=$((PORT + 1))
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
CP="$SCRIPT_DIR/target/test-classes:$SCRIPT_DIR/target/classes:$(cat "$SCRIPT_DIR/target/classpath.txt")"
java -cp "$CP" dev.infrahack.fraud.BrokerCheck ports "$PORT"
mkdir -p "$DIR/data" "$DIR/logs"
cat > "$DIR/server.properties" <<EOF
process.roles=broker,controller
node.id=1
controller.quorum.bootstrap.servers=localhost:$CONTROLLER_PORT
listeners=PLAINTEXT://localhost:$PORT,CONTROLLER://localhost:$CONTROLLER_PORT
advertised.listeners=PLAINTEXT://localhost:$PORT,CONTROLLER://localhost:$CONTROLLER_PORT
controller.listener.names=CONTROLLER
inter.broker.listener.name=PLAINTEXT
listener.security.protocol.map=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT
log.dirs=$DIR/data
num.partitions=1
offsets.topic.replication.factor=1
share.coordinator.state.topic.replication.factor=1
share.coordinator.state.topic.min.isr=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
EOF
export LOG_DIR="$DIR/logs"
kafka-storage format --standalone -t "$CLUSTER_ID" -c "$DIR/server.properties"
exec kafka-server-start "$DIR/server.properties"
