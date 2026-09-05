package uk.gov.defra.trade.imports.plants.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static uk.gov.defra.trade.imports.plants.notification.ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN;

import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Asserts against {@link ReferenceNumberGenerator#REFERENCE_NUMBER_PATTERN} rather than a
 * literal. The human-facing type code is not yet agreed, so the reference carries no prefix.
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
    void generate_shouldStartWithTheCurrentTwoDigitYear() {
        // When
        String result = generator.generate();

        // Then
        assertThat(result).startsWith(CURRENT_YY + "-");
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

    @Test
    void referenceNumberPattern_shouldRejectAPrefixedReference() {
        // Given a reference minted for a different journey
        String prefixedReference = "XX-26-ABC123";

        // When / Then
        assertThat(prefixedReference).doesNotMatch(REFERENCE_NUMBER_PATTERN);
    }
}
