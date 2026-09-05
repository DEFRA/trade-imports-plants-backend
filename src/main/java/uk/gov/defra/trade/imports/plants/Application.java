package uk.gov.defra.trade.imports.plants;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import uk.gov.defra.trade.imports.plants.configuration.NotificationTtlConfig;

@SpringBootApplication
@EnableConfigurationProperties(NotificationTtlConfig.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
