package org.promptoptimizer.lossless;

import java.util.Objects;

/**
 * One replacement over a source text. {@code removed} is the exact original text of
 * {@code [start, end)}, kept so the edit can always be reversed.
 */
public record TextEdit(int start, int end, String removed, String replacement, EditKind kind) {
    public TextEdit {
        Objects.requireNonNull(removed);
        Objects.requireNonNull(replacement);
        Objects.requireNonNull(kind);
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid edit range [" + start + "," + end + ")");
        }
        if (removed.length() != end - start) {
            throw new IllegalArgumentException("removed text length does not match range");
        }
        if (removed.equals(replacement)) {
            throw new IllegalArgumentException("no-op edit");
        }
    }

    int delta() {
        return replacement.length() - removed.length();
    }
}
