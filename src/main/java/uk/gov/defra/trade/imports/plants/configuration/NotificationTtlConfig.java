package uk.gov.defra.trade.imports.plants.configuration;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "notification.ttl")
public record NotificationTtlConfig(@Nullable @Positive Integer days, String environment) {

  public boolean isProd() {
    return "prod".equalsIgnoreCase(environment);
  }
}
