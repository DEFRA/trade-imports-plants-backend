package uk.gov.defra.trade.imports.plants;

import static org.assertj.core.api.Assertions.assertThat;

import io.swagger.v3.core.converter.ModelConverters;
import io.swagger.v3.oas.models.media.Schema;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.defra.trade.imports.plants.notification.NotificationAggregate;
import uk.gov.defra.trade.imports.plants.notification.NotificationDto;
import uk.gov.defra.trade.imports.plants.notification.NotificationFulfilmentsView;
import uk.gov.defra.trade.imports.plants.notification.NotificationView;
import uk.gov.defra.trade.imports.plants.notification.SaveNotificationDto;

/**
 * The OpenAPI schema tells a caller that each timestamp is a moment: {@code format: date-time}.
 * The format is derived from the Java type, so this fails if a timestamp is typed as a date, a
 * string or a number. It does not tell an {@code Instant} from a {@code LocalDateTime}, which
 * swagger also describes as {@code date-time}: the {@code Z} suffix on the wire is held by
 * {@code NotificationControllerTest} and {@code NotificationIT}.
 *
 * <p>The schemas are resolved with the swagger model converters springdoc itself uses, rather
 * than read from {@code /v3/api-docs}. Every schema reachable from the request and response
 * types is searched by property name, so each one that carries the field is held to the same
 * format.
 */
class OpenApiDateFormatTest {

    private static final List<Class<?>> API_TYPES = List.of(
        NotificationDto.class,
        SaveNotificationDto.class,
        NotificationAggregate.class,
        NotificationView.class,
        NotificationFulfilmentsView.class);

    @ParameterizedTest
    @ValueSource(strings = {"created", "updated", "submittedAt"})
    void schema_shouldDescribeEachTimestampAsADateTime(String property) {
        List<String> formats = new ArrayList<>();
        for (Class<?> type : API_TYPES) {
            for (Schema<?> schema : ModelConverters.getInstance().readAll(type).values()) {
                Map<String, Schema> properties = schema.getProperties();
                if (properties != null && properties.containsKey(property)) {
                    formats.add(properties.get(property).getFormat());
                }
            }
        }

        assertThat(formats)
            .as("format of every '%s' property in the API schemas", property)
            .isNotEmpty()
            .containsOnly("date-time");
    }
}
