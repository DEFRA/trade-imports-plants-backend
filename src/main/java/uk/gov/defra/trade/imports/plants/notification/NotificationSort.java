package uk.gov.defra.trade.imports.plants.notification;

import org.springframework.data.domain.Sort;

/**
 * Parses the {@code sort} query parameter sent by the frontend (e.g. {@code createdAt,asc}).
 *
 * <p>Only {@code createdAt} is recognised, and it is also the default — plants has no content
 * field worth sorting on yet. Anything unrecognised falls back to the default rather than failing
 * the request.
 */
public final class NotificationSort {

    private static final String CREATED_AT_FIELD = "created";

    private NotificationSort() {
    }

    public static Sort toSort(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return defaultSort();
        }

        String[] parts = sortParam.split(",");
        if (parts.length != 2) {
            return defaultSort();
        }

        String sortField = parts[0].trim();
        Sort.Direction sortDirection = "asc".equalsIgnoreCase(parts[1].trim())
            ? Sort.Direction.ASC
            : Sort.Direction.DESC;

        return switch (sortField) {
            case "createdAt" -> Sort.by(sortDirection, CREATED_AT_FIELD);
            default -> defaultSort();
        };
    }

    private static Sort defaultSort() {
        return Sort.by(Sort.Direction.DESC, CREATED_AT_FIELD);
    }
}
