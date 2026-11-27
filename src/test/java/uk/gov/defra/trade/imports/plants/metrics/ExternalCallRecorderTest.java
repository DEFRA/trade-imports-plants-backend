package uk.gov.defra.trade.imports.plants.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.plants.testsupport.CapturedLogs.assertOneLine;
import static uk.gov.defra.trade.imports.plants.testsupport.CapturedLogs.keyValues;
import static uk.gov.defra.trade.imports.plants.testsupport.CapturedLogs.loggedBy;
import static uk.gov.defra.trade.imports.plants.testsupport.ExternalCallMeters.MONGODB_FIND;
import static uk.gov.defra.trade.imports.plants.testsupport.ExternalCallMeters.MONGODB_INSERT;
import static uk.gov.defra.trade.imports.plants.testsupport.ExternalCallMeters.durationSampleCount;
import static uk.gov.defra.trade.imports.plants.testsupport.ExternalCallMeters.failureSampleCount;
import static uk.gov.defra.trade.imports.plants.testsupport.ExternalCallMeters.failureTotal;
import static uk.gov.defra.trade.imports.plants.testsupport.ExternalCallMeters.totalDurationMillis;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExternalCallRecorderTest {

  private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
  private final ExternalCallRecorder recorder = new ExternalCallRecorder(meterRegistry);

  @Test
  void recordSuccess_shouldRecordTheDurationAndAZeroFailure() {
    recorder.recordSuccess(MONGODB_INSERT, Duration.ofMillis(7));

    assertThat(durationSampleCount(meterRegistry, MONGODB_INSERT)).isEqualTo(1);
    assertThat(totalDurationMillis(meterRegistry, MONGODB_INSERT)).isEqualTo(7);
    assertThat(failureSampleCount(meterRegistry, MONGODB_INSERT)).isEqualTo(1);
    assertThat(failureTotal(meterRegistry, MONGODB_INSERT)).isZero();
  }

  @Test
  void recordFailure_shouldRecordTheDurationAndAFailureOfOne() {
    recorder.recordFailure(MONGODB_FIND, Duration.ofMillis(3), new IllegalStateException());

    assertThat(durationSampleCount(meterRegistry, MONGODB_FIND)).isEqualTo(1);
    assertThat(totalDurationMillis(meterRegistry, MONGODB_FIND)).isEqualTo(3);
    assertThat(failureSampleCount(meterRegistry, MONGODB_FIND)).isEqualTo(1);
    assertThat(failureTotal(meterRegistry, MONGODB_FIND)).isEqualTo(1);
  }

  @Test
  void recordSuccess_shouldLogOneInfoLineWithTheEventFieldsAndANanosecondDuration() {
    List<ILoggingEvent> lines =
        loggedBy(
            ExternalCallRecorder.class,
            () -> recorder.recordSuccess(MONGODB_INSERT, Duration.ofMillis(2)));

    ILoggingEvent line =
        assertOneLine(lines, Level.INFO, "External call mongodb/insert success in 2 ms");
    assertThat(keyValues(line))
        .containsEntry("event.category", "external-call")
        .containsEntry("event.action", "mongodb/insert")
        .containsEntry("event.outcome", "success")
        .containsEntry("event.duration", 2_000_000L);
  }

  @Test
  void recordFailure_shouldLogOneWarnLineWithTheErrorTypeAndMessageButNoStackTrace() {
    IllegalStateException cause = new IllegalStateException("The collection does not exist");

    List<ILoggingEvent> lines =
        loggedBy(
            ExternalCallRecorder.class,
            () -> recorder.recordFailure(MONGODB_FIND, Duration.ofMillis(4), cause));

    ILoggingEvent line =
        assertOneLine(lines, Level.WARN, "External call mongodb/find failure in 4 ms");
    assertThat(keyValues(line))
        .containsEntry("event.action", "mongodb/find")
        .containsEntry("event.outcome", "failure")
        .containsEntry("error.type", "java.lang.IllegalStateException")
        .containsEntry("error.message", "The collection does not exist");
    assertThat(line.getThrowableProxy()).isNull();
  }
}
