package uk.gov.defra.trade.imports.plants.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

class NotificationSortTest {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "created");

    @Test
    void toSort_shouldSortByCreatedDescending_whenParamIsNull() {
        assertThat(NotificationSort.toSort(null)).isEqualTo(DEFAULT_SORT);
    }

    @Test
    void toSort_shouldSortByCreatedDescending_whenParamIsBlank() {
        assertThat(NotificationSort.toSort("  ")).isEqualTo(DEFAULT_SORT);
    }

    @Test
    void toSort_shouldSortByCreatedAscending_whenAscRequested() {
        assertThat(NotificationSort.toSort("createdAt,asc"))
            .isEqualTo(Sort.by(Sort.Direction.ASC, "created"));
    }

    @Test
    void toSort_shouldSortByCreatedDescending_whenDescRequested() {
        assertThat(NotificationSort.toSort("createdAt,desc")).isEqualTo(DEFAULT_SORT);
    }

    @Test
    void toSort_shouldFallBackToDefault_whenFieldIsUnrecognised() {
        // Given a sort key from another journey
        assertThat(NotificationSort.toSort("arrivalDate,asc")).isEqualTo(DEFAULT_SORT);
    }

    @Test
    void toSort_shouldFallBackToDefault_whenParamIsMalformed() {
        assertThat(NotificationSort.toSort("createdAt")).isEqualTo(DEFAULT_SORT);
    }
}
