package dev.sterner.guardvillagers.common.professionalstorage;

import java.util.Objects;
import java.util.Optional;

/** Stable persistence key for a professional career metric. */
public record ProfessionalMetricId(String value) implements Comparable<ProfessionalMetricId> {
    public ProfessionalMetricId {
        value = Objects.requireNonNull(value, "value");
        if (!isValid(value)) {
            throw new IllegalArgumentException("Invalid professional metric ID: " + value);
        }
    }

    public static Optional<ProfessionalMetricId> parse(String value) {
        return isValid(value) ? Optional.of(new ProfessionalMetricId(value)) : Optional.empty();
    }

    private static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if ((character < 'a' || character > 'z')
                    && (character < '0' || character > '9')
                    && character != '.'
                    && character != '_'
                    && character != '-') {
                return false;
            }
        }
        return true;
    }

    @Override
    public int compareTo(ProfessionalMetricId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
