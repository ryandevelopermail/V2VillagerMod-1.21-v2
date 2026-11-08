package dev.sterner.guardvillagers.common.professionalstorage;

import java.util.Objects;

/** One immutable, presentation-ready row in the professional storage side panel. */
public record ProfessionalStorageRow(String label, String value, Tone tone) {
    public ProfessionalStorageRow {
        label = Objects.requireNonNull(label, "label");
        value = Objects.requireNonNull(value, "value");
        tone = Objects.requireNonNull(tone, "tone");
    }

    public enum Tone {
        NORMAL,
        PAIRED,
        WARNING,
        BLOCKER
    }
}
