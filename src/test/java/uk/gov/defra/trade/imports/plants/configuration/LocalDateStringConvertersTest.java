package uk.gov.defra.trade.imports.plants.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.TimeZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.defra.trade.imports.plants.configuration.LocalDateStringConverters.LocalDateToStringConverter;
import uk.gov.defra.trade.imports.plants.configuration.LocalDateStringConverters.StringToLocalDateConverter;

class LocalDateStringConvertersTest {

    private static final LocalDate BST_SUMMER_DATE = LocalDate.of(2026, 7, 21);
    private static final String STORED = "2026-07-21";

    private TimeZone originalZone;

    @BeforeEach
    void rememberZone() {
        originalZone = TimeZone.getDefault();
    }

    @AfterEach
    void restoreZone() {
        TimeZone.setDefault(originalZone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void write_shouldStoreTheIsoDateString_whateverTheJvmDefaultZone(String zoneId) {
        // Given
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

        // When & Then
        assertThat(new LocalDateToStringConverter().convert(BST_SUMMER_DATE)).isEqualTo(STORED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void read_shouldReturnTheSameCalendarDate_whateverTheJvmDefaultZone(String zoneId) {
        // Given
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

        // When & Then
        assertThat(new StringToLocalDateConverter().convert(STORED)).isEqualTo(BST_SUMMER_DATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London", "America/New_York", "Australia/Sydney"})
    void roundTrip_shouldPreserveTheCalendarDate_whateverTheJvmDefaultZone(String zoneId) {
        // Given
        TimeZone.setDefault(TimeZone.getTimeZone(zoneId));

        // When
        String stored = new LocalDateToStringConverter().convert(BST_SUMMER_DATE);

        // Then
        assertThat(new StringToLocalDateConverter().convert(stored)).isEqualTo(BST_SUMMER_DATE);
    }
}
