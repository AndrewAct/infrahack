package dev.infrahack.fraud;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Frozen single-segment, warm-cache cosine comparison. No production service abstraction. */
public final class RetrievalBenchmark {
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final String ENDPOINT = System.getProperty("os.endpoint", "http://localhost:19200");
    static final int QUERIES = 12, REPEATS = 3;
    static final long CUTOFF = 1793000000000L;
    static final String FAMILY = "synthetic-enriched-payment-v1";
    static final float[][] CENTERS = new float[8][200];
    static {
        var r = new Random(CaseEncoder.SEED + 1);
        for (var center : CENTERS) for (int j = 0; j < 200; j++) center[j] = (float) r.nextGaussian();
    }
    static float[] fields(int episode) {
        var r = new Random(CaseEncoder.SEED ^ (episode * 1000003L));
        int cluster = Math.floorMod(episode, CENTERS.length);
        float[] x = new float[200];
        for (int j = 0; j < x.length; j++) x[j] = (float) (CENTERS[cluster][j] + 0.65 * r.nextGaussian());
        return x;
    }
    static void save(Path file, Object value) throws Exception {
        PaymentJson.JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), value);
    }
    static JsonNode call(String method, String path, Object body) throws Exception {
        String text = body == null ? null : body instanceof String s ? s : PaymentJson.JSON.writeValueAsString(body);
        var request = HttpRequest.newBuilder(URI.create(ENDPOINT + path)).timeout(Duration.ofSeconds(120))
            .header("Content-Type", "application/json").method(method,
                text == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(text)).build();
        var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300) throw new IOException(method + " " + path + " " + response.statusCode() + " " + response.body());
        return PaymentJson.JSON.readTree(response.body());
    }
    static void create(String index, int d) throws Exception {
        call("PUT", "/" + index, Map.of("settings", Map.of("index.knn", true, "number_of_shards", 1,
            "number_of_replicas", 0, "refresh_interval", "-1"), "mappings", Map.of("properties", Map.of(
            "vector", Map.of("type", "knn_vector", "dimension", d, "method", Map.of("name", "hnsw", "engine", "lucene",
                "space_type", "cosinesimil", "parameters", Map.of("m", 16, "ef_construction", 100))),
            "tenant", Map.of("type", "keyword"), "account", Map.of("type", "keyword"), "version", Map.of("type", "keyword"),
            "currency", Map.of("type", "keyword"), "complete", Map.of("type", "boolean"),
            "case_time", Map.of("type", "long"), "knowledge_time", Map.of("type", "long")))));
    }
    static ObjectNode doc(String account, float[] vector) {
        var n = PaymentJson.JSON.createObjectNode().put("tenant", "synthetic").put("account", account)
            .put("version", FAMILY + "/" + CaseEncoder.VERSION).put("currency", "USD").put("complete", true)
            .put("case_time", CUTOFF - 60000).put("knowledge_time", CUTOFF - 30000);
        n.set("vector", PaymentJson.JSON.valueToTree(vector));
        return n;
    }
    static void append(StringBuilder bulk, String id, ObjectNode doc) { bulk.append("{\"create\":{\"_id\":\"").append(id).append("\"}}\n").append(doc).append('\n'); }
    static void bulk(String index, StringBuilder body) throws Exception {
        var result = call("POST", "/" + index + "/_bulk", body.toString());
        if (result.path("errors").asBoolean()) throw new IOException("bulk item failure " + result);
        body.setLength(0);
    }
    static Map<String, Object> predicate(boolean selective) {
        var filters = new ArrayList<Object>();
        filters.add(Map.of("term", Map.of("tenant", "synthetic")));
        filters.add(Map.of("term", Map.of("currency", "USD")));
        filters.add(Map.of("term", Map.of("version", FAMILY + "/" + CaseEncoder.VERSION)));
        filters.add(Map.of("term", Map.of("complete", true)));
        filters.add(Map.of("range", Map.of("case_time", Map.of("lt", CUTOFF))));
        filters.add(Map.of("range", Map.of("knowledge_time", Map.of("lt", CUTOFF))));
        if (selective) filters.add(Map.of("ids", Map.of("values", List.of("c0", "c1", "c2", "c3", "c4"))));
        return Map.of("bool", Map.of("filter", filters, "must_not", List.of(Map.of("term", Map.of("account", "query-account")))));
    }
    static Map<String, Object> query(float[] vector, boolean exact, int breadth, boolean selective) {
        Object q = exact ? Map.of("script_score", Map.of("query", predicate(selective), "script", Map.of(
            "lang", "knn", "source", "knn_score", "params", Map.of("field", "vector", "query_value", vector, "space_type", "cosinesimil"))))
            : Map.of("knn", Map.of("vector", Map.of("vector", vector, "k", 10, "method_parameters", Map.of("ef_search", breadth), "filter", predicate(selective))));
        return Map.of("size", 10, "_source", false, "query", q);
    }
    static List<String> ids(JsonNode response) {
        var out = new ArrayList<String>();
        response.path("hits").path("hits").forEach(hit -> out.add(hit.path("_id").asText()));
        return out;
    }
    static double cosine(float[] a, float[] b) {
        double dot = 0, aa = 0, bb = 0;
        for (int i = 0; i < a.length; i++) { dot += (double) a[i] * b[i]; aa += (double) a[i] * a[i]; bb += (double) b[i] * b[i]; }
        return dot / Math.sqrt(aa * bb);
    }
    record Hit(int id, double score) {}
    static List<Hit> oracle(float[][] corpus, float[] q) {
        var top = new PriorityQueue<Hit>(Comparator.comparingDouble(Hit::score));
        for (int i = 0; i < corpus.length; i++) {
            var hit = new Hit(i, cosine(corpus[i], q));
            if (top.size() < 10) top.add(hit);
            else if (hit.score > top.peek().score) { top.remove(); top.add(hit); }
        }
        return top.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()).toList();
    }
    static double recall(List<String> result, List<Hit> expected, float[][] corpus, float[] q) {
        if (result.size() != 10) throw new AssertionError("result shortfall " + result);
        double threshold = expected.getLast().score;
        return result.stream().filter(id -> id.matches("c[0-9]+"))
            .mapToInt(id -> cosine(corpus[Integer.parseInt(id.substring(1))], q) + 1e-6 >= threshold ? 1 : 0).sum() / 10.0;
    }
    static Map<String, Object> path(String index, float[] q, int breadth, boolean selective) throws Exception {
        call("POST", "/" + index + "/_search?request_cache=false", query(q, false, breadth, selective));
        try (var probe = new VectorPathProbe(15005)) {
            var evidence = new ArrayList<Object>();
            var encoder = new CaseEncoder(q.length);
            for (int i = 0; i < (selective ? 1 : QUERIES); i++) {
                long a0 = probe.approximate.get(), e0 = probe.exact.get();
                var response = call("POST", "/" + index + "/_search?request_cache=false", query(encoder.encode(fields(1000000 + i)), false, breadth, selective));
                Thread.sleep(100);
                long a = probe.approximate.get() - a0, e = probe.exact.get() - e0;
                if (selective ? e == 0 || a != 0 : a == 0 || e != 0) throw new AssertionError("unexpected path for query " + i + ": " + a + "/" + e);
                evidence.add(Map.of("query", i, "approximate_entries", a, "exact_entries", e, "returned_ids", ids(response)));
            }
            return Map.of("queries", evidence, "debugger_detached_before_timing", true);
        }
    }
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args[0]); Files.createDirectories(output);
        var manifest = new LinkedHashMap<String, Object>();
        manifest.put("seed", CaseEncoder.SEED); manifest.put("encoder", CaseEncoder.VERSION);
        manifest.put("query_count", QUERIES); manifest.put("repetitions", REPEATS); manifest.put("warmup_passes", 2);
        manifest.put("java", System.getProperty("java.runtime.version")); manifest.put("os", call("GET", "/", null));
        manifest.put("input_fields", 200); manifest.put("query_episode_ids", "1000000..1000011; disjoint from c0..c99999");
        manifest.put("semantics", "200 standardized synthetic continuous fields, 8 latent templates plus independent noise; no learned semantics or online multi-key features");
        manifest.put("field_groups", List.of("payment-window:0..39", "device-window:40..79", "network-window:80..119", "account-window:120..159", "order-window:160..199"));
        save(output.resolve("manifest.json"), manifest);
        save(output.resolve("input-samples.json"), Map.of("corpus0", fields(0), "heldout0", fields(1000000)));
        try (var samples = Files.newBufferedWriter(output.resolve("samples.jsonl"))) {
            for (int n : new int[]{10000, 100000}) for (int d : new int[]{8, 64, 256, 768}) {
                String index = "fraud-p2-" + output.getFileName().toString().toLowerCase(Locale.ROOT) + "-" + n + "-" + d;
                System.out.println("POINT " + n + " x " + d);
                var encoder = new CaseEncoder(d);
                float[][] corpus = new float[n][], queries = new float[QUERIES][];
                for (int i = 0; i < QUERIES; i++) queries[i] = encoder.encode(fields(1000000 + i));
                create(index, d);
                try {
                    long buildStart = System.nanoTime();
                    var body = new StringBuilder();
                    try (var vectors = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output.resolve("vectors-" + n + "-" + d + ".f32"))))) {
                        for (int i = 0; i < n; i++) {
                            corpus[i] = encoder.encode(fields(i));
                            for (float v : corpus[i]) vectors.writeFloat(v);
                            append(body, "c" + i, doc("account-" + i, corpus[i]));
                            if ((i + 1) % 100 == 0) bulk(index, body);
                        }
                    }
                    for (int i = 0; i < 6; i++) {
                        var canary = doc("canary", queries[0]);
                        switch (i) {
                            case 0 -> canary.put("tenant", "other"); case 1 -> canary.put("currency", "EUR");
                            case 2 -> canary.put("case_time", CUTOFF + 1); case 3 -> canary.put("account", "query-account");
                            case 4 -> canary.put("version", "incompatible"); case 5 -> canary.put("complete", false);
                        }
                        append(body, "canary" + i, canary);
                    }
                    bulk(index, body); call("POST", "/" + index + "/_refresh", null);
                    double buildSeconds = (System.nanoTime() - buildStart) / 1e9;
                    long mergeStart = System.nanoTime(); call("POST", "/" + index + "/_forcemerge?max_num_segments=1", null);
                    var point = new LinkedHashMap<String, Object>(); point.put("n", n); point.put("dimension", d); point.put("build_seconds", buildSeconds);
                    point.put("merge_seconds", (System.nanoTime() - mergeStart) / 1e9);
                    long eligible = call("POST", "/" + index + "/_count", Map.of("query", predicate(false))).path("count").asLong();
                    if (eligible != n) throw new AssertionError("wrong eligibility " + eligible);
                    point.put("segments", call("GET", "/" + index + "/_segments", null));
                    point.put("eligible_count", eligible); point.put("graph_path", path(index, queries[0], 100, false));
                    point.put("selective_exact_path", path(index, queries[0], 100, true));
                    save(output.resolve("queries-" + n + "-" + d + ".json"), queries);
                    List<List<Hit>> expected = new ArrayList<>();
                    var oracleIds = new ArrayList<Object>();
                    for (int q = 0; q < QUERIES; q++) {
                        var oracle = oracle(corpus, queries[q]); expected.add(oracle);
                        var actual = ids(call("POST", "/" + index + "/_search", query(queries[q], true, 100, false)));
                        if (recall(actual, oracle, corpus, queries[q]) != 1) throw new AssertionError("exact disagrees with oracle");
                        oracleIds.add(Map.of("query", q, "oracle", oracle, "exact", actual));
                    }
                    save(output.resolve("oracle-" + n + "-" + d + ".json"), oracleIds);
                    int[] breadths = d == 256 ? new int[]{32, 100, 200} : new int[]{100};
                    if (d == 256) for (int breadth : new int[]{32, 200}) point.put("graph_path_" + breadth, path(index, queries[0], breadth, false));
                    for (int warm = 0; warm < 2; warm++) for (var q : queries) {
                        call("POST", "/" + index + "/_search", query(q, true, 100, false));
                        for (int breadth : breadths) call("POST", "/" + index + "/_search", query(q, false, breadth, false));
                    }
                    for (int rep = 0; rep < REPEATS; rep++) for (int qi = 0; qi < QUERIES; qi++) {
                        int q = rep % 2 == 0 ? qi : QUERIES - 1 - qi;
                        var modes = new ArrayList<Integer>(); modes.add(0); for (int breadth : breadths) modes.add(breadth);
                        if (rep % 2 == 1) Collections.reverse(modes);
                        for (int mode : modes) {
                            long start = System.nanoTime();
                            var response = call("POST", "/" + index + "/_search", query(queries[q], mode == 0, mode, false));
                            double millis = (System.nanoTime() - start) / 1e6;
                            var resultIds = ids(response);
                            double recall = recall(resultIds, expected.get(q), corpus, queries[q]);
                            if (mode == 0 && recall != 1) throw new AssertionError("exact changed");
                            samples.write(PaymentJson.JSON.writeValueAsString(Map.of("n", n, "d", d, "rep", rep, "query", q,
                                "mode", mode == 0 ? "exact" : "hnsw-" + mode, "millis", millis, "server_took_ms", response.path("took").asInt(), "recall", recall, "ids", resultIds, "wall_ms", System.currentTimeMillis())));
                            samples.newLine(); samples.flush();
                        }
                    }
                    point.put("index_stats", call("GET", "/" + index + "/_stats", null));
                    point.put("node_stats", call("GET", "/_nodes/stats/jvm,process,os,indices", null));
                    save(output.resolve("point-" + n + "-" + d + ".json"), point);
                } finally { call("DELETE", "/" + index, null); }
            }
        }
        System.out.println("PASS static retrieval matrix " + output);
    }
}
