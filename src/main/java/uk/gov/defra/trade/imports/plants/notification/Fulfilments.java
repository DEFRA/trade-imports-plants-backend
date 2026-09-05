package uk.gov.defra.trade.imports.plants.notification;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.bson.Document;

/** Helpers for the opaque obligation-fulfilment payload. */
final class Fulfilments {

    private Fulfilments() {
    }

    /**
     * BSON round-trip deep copy of a fulfilments list. Callers need independence from the source
     * because amend snapshots the pre-amend fulfilments into {@code submittedFulfilmentsBaseline}
     * and cancel-amend restores from it; a shared reference at any nesting depth would let a later
     * in-memory mutation on one list surface on the other before the notification is persisted.
     *
     * @param source the list to copy; may be {@code null}
     * @return a fresh independent list, or {@code null} when {@code source} is {@code null}
     */
    static List<Document> deepCopy(List<Document> source) {
        if (source == null) {
            return null;
        }
        return source.stream()
            .map(document -> Document.parse(document.toJson()))
            .collect(Collectors.toCollection(ArrayList::new));
    }
}
