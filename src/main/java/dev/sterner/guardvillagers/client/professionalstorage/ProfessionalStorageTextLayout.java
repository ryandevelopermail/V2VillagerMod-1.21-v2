package dev.sterner.guardvillagers.client.professionalstorage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/** Width-aware word wrapping that only ellipsizes a single unbreakable token. */
public final class ProfessionalStorageTextLayout {
    private static final String ELLIPSIS = "…";

    private ProfessionalStorageTextLayout() {
    }

    public static WrappedText wrap(String text, int maximumWidth, ToIntFunction<String> width) {
        if (maximumWidth <= 0) {
            return new WrappedText(List.of(""), true);
        }
        String normalized = text == null ? "" : text.strip();
        if (normalized.isEmpty()) {
            return new WrappedText(List.of(""), false);
        }

        List<String> lines = new ArrayList<>();
        boolean ellipsized = false;
        String current = "";
        for (String word : normalized.split("\\s+")) {
            if (width.applyAsInt(word) > maximumWidth) {
                if (!current.isEmpty()) {
                    lines.add(current);
                    current = "";
                }
                lines.add(ellipsizeToken(word, maximumWidth, width));
                ellipsized = true;
                continue;
            }
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (width.applyAsInt(candidate) <= maximumWidth) {
                current = candidate;
            } else {
                lines.add(current);
                current = word;
            }
        }
        if (!current.isEmpty()) {
            lines.add(current);
        }
        return new WrappedText(lines.isEmpty() ? List.of("") : lines, ellipsized);
    }

    private static String ellipsizeToken(String token, int maximumWidth, ToIntFunction<String> width) {
        if (width.applyAsInt(ELLIPSIS) > maximumWidth) {
            return "";
        }
        int low = 0;
        int high = token.length();
        while (low < high) {
            int middle = (low + high + 1) / 2;
            String candidate = token.substring(0, middle) + ELLIPSIS;
            if (width.applyAsInt(candidate) <= maximumWidth) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        return token.substring(0, low) + ELLIPSIS;
    }

    public record WrappedText(List<String> lines, boolean ellipsized) {
        public WrappedText {
            lines = List.copyOf(lines);
        }
    }
}
