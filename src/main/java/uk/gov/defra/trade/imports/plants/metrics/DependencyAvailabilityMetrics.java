package uk.gov.defra.trade.imports.plants.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class DependencyAvailabilityMetrics {

  DependencyAvailabilityMetrics(MeterRegistry meterRegistry, MongoAvailability mongoAvailability) {
    Gauge.builder(
            "external.dependency.available", () -> gaugeValueOf(mongoAvailability.state()))
        .tag(ExternalCallRecorder.DEPENDENCY_TAG, Dependency.MONGODB.tagValue())
        .register(meterRegistry);
  }

  private static double gaugeValueOf(Availability availability) {
    return switch (availability) {
      case AVAILABLE -> 1;
      case UNAVAILABLE -> 0;
      case UNKNOWN -> Double.NaN;
    };
  }
}
