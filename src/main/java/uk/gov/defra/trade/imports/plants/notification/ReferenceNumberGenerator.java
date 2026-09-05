package uk.gov.defra.trade.imports.plants.notification;

import java.security.SecureRandom;
import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * Generates notification reference numbers in the format {@code {YY}-{XXXXXX}}.
 *
 * <p>{@code XXXXXX} is a 6-character Crockford base32 random body (digits 0–9 and letters A–Z
 * excluding I, L, O, U), drawn from {@link SecureRandom}. Collision detection and retry on
 * persistence failure are handled by the caller.
 */
@Component
public class ReferenceNumberGenerator {

    /**
     * Must stay a compile-time constant: it is used as
     * {@code @Pattern(regexp = ReferenceNumberGenerator.REFERENCE_NUMBER_PATTERN)} on controller
     * path variables. Concatenating {@code static final} strings is fine; computing it in a static
     * block or via {@code String.format} is not and will fail the build.
     */
    public static final String REFERENCE_NUMBER_PATTERN = "^\\d{2}-[0-9A-HJ-KM-NP-TV-Z]{6}$";

    private static final String CROCKFORD_BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
    private static final int REF_RANDOM_LENGTH = 6;
    private static final int TWO_DIGIT_YEAR_MODULUS = 100;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /**
     * Mints a reference number for a new notification.
     *
     * @return a reference number of the form {@code {YY}-{XXXXXX}}
     */
    public String generate() {
        String yy = "%02d".formatted(LocalDate.now().getYear() % TWO_DIGIT_YEAR_MODULUS);
        // PENDING REQUIREMENTS: the human-facing type code that prefixes the reference number is
        // not yet agreed. Prepend it here, and to the frontend's stub mintReferenceNumber, when it
        // is. Note that already-persisted reference numbers carry whatever form was in force when
        // they were minted, and referenceNumber has a unique index — changing this after data
        // exists is a migration, not an edit.
        return "%s-%s".formatted(yy, randomBase32());
    }

    private static String randomBase32() {
        StringBuilder sb = new StringBuilder(REF_RANDOM_LENGTH);
        for (int i = 0; i < REF_RANDOM_LENGTH; i++) {
            sb.append(CROCKFORD_BASE32.charAt(SECURE_RANDOM.nextInt(CROCKFORD_BASE32.length())));
        }
        return sb.toString();
    }
}
