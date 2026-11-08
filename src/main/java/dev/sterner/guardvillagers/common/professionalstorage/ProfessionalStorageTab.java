package dev.sterner.guardvillagers.common.professionalstorage;

import java.util.List;
import java.util.Objects;

/** One immutable, profession-authored tab in the professional storage panel. */
public record ProfessionalStorageTab(
        String id,
        String title,
        List<ProfessionalStorageRow> rows
) {
    public ProfessionalStorageTab {
        id = Objects.requireNonNull(id, "id");
        title = Objects.requireNonNull(title, "title");
        rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
        if (title.isBlank()) {
            throw new IllegalArgumentException("Professional storage tab titles cannot be blank");
        }
    }
}
