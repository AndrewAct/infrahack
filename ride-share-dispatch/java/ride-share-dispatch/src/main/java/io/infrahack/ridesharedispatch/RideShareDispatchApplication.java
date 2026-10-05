package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.config.DispatchProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableConfigurationProperties(DispatchProperties.class)
@EnableScheduling
public class RideShareDispatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(RideShareDispatchApplication.class, args);
    }
}
