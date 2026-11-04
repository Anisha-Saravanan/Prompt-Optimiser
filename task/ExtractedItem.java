package org.promptoptimizer.task;

import java.util.List;
import java.util.Objects;

/**
 * One item of a {@link TaskProfile}. {@code text} is always an exact slice of the canonical prompt
 * (never paraphrased), so an item cannot be invented.
 *
 * @param id               stable id within the profile (T1, R1, C1, O1, or X1 for unpromoted candidates)
 * @param kind             role of the item
 * @param label            sub-type: constraints use prohibition/limit/preservation/restriction;
 *                         others use task/requirement/format
 * @param text             exact source text
 * @param provenance       where and why
 * @param confidence       ordinal confidence
 * @param duplicateSources other places in the prompt that repeat this item verbatim (normalized)
 */
public record ExtractedItem(String id, ItemKind kind, String label, String text,
                            Provenance provenance, Confidence confidence,
                            List<SourceRef> duplicateSources) {
    public ExtractedItem {
        Objects.requireNonNull(id);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(label);
        Objects.requireNonNull(text);
        Objects.requireNonNull(provenance);
        Objects.requireNonNull(confidence);
        if (text.isBlank()) {
            throw new IllegalArgumentException("item text must not be blank");
        }
        duplicateSources = List.copyOf(duplicateSources);
    }

    public SourceRef source() {
        return provenance.source();
    }
}
