package io.infrahack.moviewatchlistdb;

import java.util.Map;

import io.infrahack.moviewatchlistdb.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class MovieWatchlistDbApplication {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        SpringApplication app = new SpringApplication(MovieWatchlistDbApplication.class);
        app.setDefaultProperties(Map.of("server.port", config.serverPort()));
        app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton("appConfig", config));
        app.run(args);
    }
}
