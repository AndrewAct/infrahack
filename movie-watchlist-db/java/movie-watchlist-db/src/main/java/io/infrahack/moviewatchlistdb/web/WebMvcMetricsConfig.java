package io.infrahack.moviewatchlistdb.web;

import io.infrahack.moviewatchlistdb.observability.Metrics;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcMetricsConfig implements WebMvcConfigurer {

    private final Metrics metrics;

    public WebMvcMetricsConfig(Metrics metrics) {
        this.metrics = metrics;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new MetricsInterceptor(metrics));
    }
}
