package uk.gov.defra.trade.imports.plants.configuration;

import jakarta.annotation.Nullable;
import jakarta.validation.constraints.Positive;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "notification.ttl")
public record NotificationTtlConfig(@Nullable @Positive Integer days, String environment) {

  public boolean isProd() {
    return "prod".equalsIgnoreCase(environment);
  }

  /**
   * The days after creation a new notification's expiry is stamped for.
   *
   * @return the configured days, or empty in prod or when no days are set
   */
  public Optional<Integer> daysToStamp() {
    if (isProd()) {
      return Optional.empty();
    }
    return Optional.ofNullable(days);
  }
}
