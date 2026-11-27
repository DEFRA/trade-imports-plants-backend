package uk.gov.defra.trade.imports.plants.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.plants.testsupport.ExternalCallMeters.availability;
import static uk.gov.defra.trade.imports.plants.testsupport.MongoClusterEvents.failing;
import static uk.gov.defra.trade.imports.plants.testsupport.MongoClusterEvents.primary;
import static uk.gov.defra.trade.imports.plants.testsupport.MongoClusterEvents.report;

import com.mongodb.ServerAddress;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DependencyAvailabilityMetricsTest {

  private static final ServerAddress ADDRESS = new ServerAddress("mongo-1", 27017);

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final MongoAvailability mongoAvailability = new MongoAvailability();

  @BeforeEach
  void registerTheGauge() {
    new DependencyAvailabilityMetrics(meterRegistry, mongoAvailability);
  }

  @Test
  void availabilityGauge_shouldBeNaNForMongodb_whenNoServerCheckHasBeenReported() {
    assertThat(availability(meterRegistry, Dependency.MONGODB)).isNaN();
  }

  @Test
  void availabilityGauge_shouldBeOneForMongodb_whenMongoIsAvailable() {
    report(mongoAvailability, primary(ADDRESS));

    assertThat(availability(meterRegistry, Dependency.MONGODB)).isEqualTo(1);
  }

  @Test
  void availabilityGauge_shouldBeZeroForMongodb_whenMongoIsUnavailable() {
    report(mongoAvailability, failing(ADDRESS));

    assertThat(availability(meterRegistry, Dependency.MONGODB)).isZero();
  }
}
