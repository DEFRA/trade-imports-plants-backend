package uk.gov.defra.trade.imports.plants.metrics;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.stereotype.Component;

/**
 * Records each call to a dependency as the timer {@code external.call.duration} and the
 * distribution summary {@code external.call.failure} (1 for a failure, 0 for a success), both
 * tagged {@code dependency} and {@code operation}, and logs it with the ECS {@code event.*} fields.
 * A failure's line also carries the ECS {@code error.type} and {@code error.message} fields.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExternalCallRecorder {

  static final String DEPENDENCY_TAG = "dependency";

  private final MeterRegistry meterRegistry;

  /**
   * Records a call that succeeded.
   *
   * @param call the call made
   * @param elapsed how long the call took
   */
  public void recordSuccess(ExternalCall call, Duration elapsed) {
    durationOf(call).record(elapsed);
    failureOf(call).record(0);
    logCall(Level.INFO, call, "success", elapsed, null);
  }

  /**
   * Records a call that failed.
   *
   * @param call the call made
   * @param elapsed how long the call took
   * @param cause why it failed
   */
  public void recordFailure(ExternalCall call, Duration elapsed, Throwable cause) {
    durationOf(call).record(elapsed);
    failureOf(call).record(1);
    logCall(Level.WARN, call, "failure", elapsed, cause);
  }

  private Timer durationOf(ExternalCall call) {
    return Timer.builder("external.call.duration").tags(tagsOf(call)).register(meterRegistry);
  }

  private DistributionSummary failureOf(ExternalCall call) {
    return DistributionSummary.builder("external.call.failure")
        .tags(tagsOf(call))
        .register(meterRegistry);
  }

  private static Tags tagsOf(ExternalCall call) {
    return Tags.of(DEPENDENCY_TAG, call.dependency().tagValue(), "operation", call.operation());
  }

  private static void logCall(
      Level level, ExternalCall call, String outcome, Duration elapsed, Throwable cause) {
    String action = call.dependency().tagValue() + "/" + call.operation();
    LoggingEventBuilder line =
        log.atLevel(level)
            .addKeyValue("event.category", "external-call")
            .addKeyValue("event.action", action)
            .addKeyValue("event.outcome", outcome)
            .addKeyValue("event.duration", elapsed.toNanos());
    if (cause != null) {
      line.addKeyValue("error.type", cause.getClass().getName())
          .addKeyValue("error.message", cause.getMessage());
    }
    line.log("External call {} {} in {} ms", action, outcome, elapsed.toMillis());
  }
}
