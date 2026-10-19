package uk.gov.defra.trade.imports.plants.configuration;

import java.time.LocalDate;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * {@link LocalDate} &harr; {@link String} MongoDB converters, registered as
 * {@code MongoCustomConversions} by {@link MongoConfig#mongoCustomConversions()}.
 *
 * <p>Every {@code LocalDate} property on every Mongo document is stored as its ISO-8601 string,
 * {@code 2026-07-21}, and reads back as the same date whatever the JVM default zone. The reader
 * runs only where the target property is a {@code LocalDate}; {@code String} fields and untyped
 * payloads holding a date-shaped string are left alone.
 *
 * @see MongoConfig#mongoCustomConversions()
 */
public final class LocalDateStringConverters {

    private LocalDateStringConverters() {
    }

    /** Writes a {@code LocalDate} as its ISO-8601 string, for example {@code 2026-07-21}. */
    @WritingConverter
    public static class LocalDateToStringConverter implements Converter<LocalDate, String> {

        @Override
        public String convert(LocalDate source) {
            return source.toString();
        }
    }

    /** Reads a stored {@code YYYY-MM-DD} string back as the calendar date it names. */
    @ReadingConverter
    public static class StringToLocalDateConverter implements Converter<String, LocalDate> {

        @Override
        public LocalDate convert(String source) {
            return LocalDate.parse(source);
        }
    }
}
