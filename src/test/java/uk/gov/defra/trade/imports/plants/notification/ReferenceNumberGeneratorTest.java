package uk.gov.defra.trade.imports.plants.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.plants.notification.ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Asserts against {@link ReferenceNumberGenerator#REFERENCE_NUMBER_PATTERN} rather than a
 * literal, with an independent assertion for the agreed plants prefix.
 */
class ReferenceNumberGeneratorTest {

    private static final String CURRENT_YY = "%02d".formatted(LocalDate.now().getYear() % 100);
    private static final String CROCKFORD_BODY_REGEX = "[0-9A-HJ-KM-NP-TV-Z]{6}";

    private ReferenceNumberGenerator generator;

    @BeforeEach
    void setUp() {
        generator = new ReferenceNumberGenerator();
    }

    @Test
    void generate_shouldReturnReferenceNumberMatchingTheValidationPattern() {
        // When
        String result = generator.generate();

        // Then — the same pattern the controller enforces on path variables
        assertThat(result).matches(REFERENCE_NUMBER_PATTERN);
    }

    @Test
    void generate_shouldStartWithThePlantsPrefixAndCurrentTwoDigitYear() {
        // When
        String result = generator.generate();

        // Then
        assertThat(result).startsWith("GBN-HRP-" + CURRENT_YY + "-");
    }

    @Test
    void generate_shouldProduceSixCharacterCrockfordBody_excludingILOU() {
        // When — sample 100 reference numbers; probability of missing an invalid char by chance is negligible
        for (int i = 0; i < 100; i++) {
            String reference = generator.generate();
            String body = reference.substring(reference.lastIndexOf('-') + 1);

            // Then
            assertThat(body)
                .hasSize(6)
                .matches(CROCKFORD_BODY_REGEX)
                .doesNotContain("I", "L", "O", "U");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"26-ABC123", "GBN-AG-26-ABC123", "GBN-HRP-26-ABC12I", "GBN-HRP-26-ABC12"})
    void referenceNumberPattern_shouldRejectInvalidReferences(String reference) {

        // When / Then
        assertThat(reference).doesNotMatch(REFERENCE_NUMBER_PATTERN);
    }
}
