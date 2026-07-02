package io.infrahack.passwordresetworkflow;

import java.util.Map;

import io.infrahack.passwordresetworkflow.config.AppConfig;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
public class PasswordResetWorkflowApplication {

    public static void main(String[] args) {
        AppConfig config = AppConfig.load();
        SpringApplication app = new SpringApplication(PasswordResetWorkflowApplication.class);
        app.setDefaultProperties(Map.of("server.port", config.serverPort()));
        app.addInitializers(ctx -> ctx.getBeanFactory().registerSingleton("appConfig", config));
        app.run(args);
    }
}
