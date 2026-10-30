package dev.infrahack.fraud;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;

/** Only the two ownership checks needed by the integration runner; no extra smoke matrix. */
public final class BrokerCheck {
    public static void main(String[] args) throws Exception {
        if (args[0].equals("ports")) {
            int port = Integer.parseInt(args[1]);
            try (var broker = new ServerSocket(port, 1, InetAddress.getByName("localhost"));
                 var controller = new ServerSocket(port + 1, 1, InetAddress.getByName("localhost"))) {}
        } else if (args[0].equals("owner")) {
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", args[1], "request.timeout.ms", "1500", "default.api.timeout.ms", "2000"))) {
                String actual = admin.describeCluster().clusterId().get(3, TimeUnit.SECONDS);
                if (!actual.equals(args[2])) throw new IllegalStateException("wrong Kafka cluster: " + actual);
            }
        } else throw new IllegalArgumentException("ports <port> | owner <bootstrap> <cluster-id>");
    }
}
