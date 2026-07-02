package io.infrahack.moviewatchlistdb.web;

import io.infrahack.moviewatchlistdb.observability.Metrics;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MetricsController {

    private static final MediaType PROMETHEUS_TEXT =
            MediaType.parseMediaType("text/plain; version=0.0.4; charset=utf-8");

    private final Metrics metrics;

    public MetricsController(Metrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    public String metrics() {
        return metrics.scrape();
    }
}
