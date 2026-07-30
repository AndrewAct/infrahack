package io.infrahack.distributedratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class DistributedratelimiterApplication {

    public static void main(String[] args) {
        SpringApplication.run(DistributedratelimiterApplication.class, args);
    }

}
