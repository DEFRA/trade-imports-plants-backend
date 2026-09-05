package uk.gov.defra.trade.imports.plants.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockingTaskExecutor;
import uk.gov.defra.trade.imports.plants.configuration.NotificationTtlConfig;

@ExtendWith(MockitoExtension.class)
class NotificationExpirySweeperTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private LockingTaskExecutor lockingTaskExecutor;

    private NotificationExpirySweeper sweeper;

    @BeforeEach
    void setUp() {
        NotificationTtlConfig ttlConfig = new NotificationTtlConfig(7, "dev",
            new NotificationTtlConfig.Sweep(
                true, 3_600_000, 25, Duration.ofSeconds(1), Duration.ofSeconds(30)));
        sweeper = new NotificationExpirySweeper(notificationService, lockingTaskExecutor, ttlConfig);
    }

    private void lockIsGranted() {
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(lockingTaskExecutor).executeWithLock(any(Runnable.class), any(LockConfiguration.class));
    }

    @Test
    void sweep_shouldDeleteExpiredNotificationsUsingTheConfiguredBatchSize() {
        // Given
        lockIsGranted();
        when(notificationService.deleteExpired(25)).thenReturn(3);

        // When
        sweeper.sweep();

        // Then
        verify(notificationService).deleteExpired(25);
    }

    @Test
    void sweep_shouldHoldTheNamedLockForTheConfiguredDurations() {
        // Given
        lockIsGranted();
        when(notificationService.deleteExpired(25)).thenReturn(0);

        // When
        sweeper.sweep();

        // Then
        ArgumentCaptor<LockConfiguration> lockCaptor =
            ArgumentCaptor.forClass(LockConfiguration.class);
        verify(lockingTaskExecutor).executeWithLock(any(Runnable.class), lockCaptor.capture());
        LockConfiguration lockConfiguration = lockCaptor.getValue();
        assertThat(lockConfiguration.getName()).isEqualTo(NotificationExpirySweeper.LOCK_NAME);
        assertThat(lockConfiguration.getLockAtMostFor()).isEqualTo(Duration.ofSeconds(30));
        assertThat(lockConfiguration.getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void sweep_shouldNotDelete_whenTheLockIsHeldElsewhere() {
        // Given the executor never runs the task, because another instance holds the lock

        // When
        sweeper.sweep();

        // Then
        verify(notificationService, never()).deleteExpired(anyInt());
    }

    @Test
    void sweep_shouldBeScheduledOnTheConfiguredInterval() throws NoSuchMethodException {
        // Given
        Method sweepMethod = NotificationExpirySweeper.class.getMethod("sweep");

        // When
        Scheduled scheduled = sweepMethod.getAnnotation(Scheduled.class);

        // Then
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedDelayString())
            .isEqualTo("${notification.ttl.sweep.interval-ms:3600000}");
    }
}
