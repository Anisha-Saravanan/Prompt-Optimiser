package org.promptoptimizer.task;

import java.util.Objects;

/**
 * Evidence for one extracted item: where it came from, which rule fired, and whether the item was
 * stated explicitly or inferred from structure/cues.
 *
 * @param source the quoted text range
 * @param rule   name of the rule that produced the item (stable identifier, for audit)
 * @param basis  how strong the evidence is
 */
public record Provenance(SourceRef source, String rule, Basis basis) {

    public enum Basis {
        /** The author labelled the content (e.g. a "CONSTRAINTS:" heading); the item is a quoted span. */
        EXPLICIT_LABEL,
        /** An unlabelled sentence matched a lexical cue (modal, negation, format word, ...). */
        CUE_RULE,
        /** Inferred from position or sentence shape only (e.g. an imperative list item). Least certain. */
        STRUCTURAL_INFERENCE
    }

    public Provenance {
        Objects.requireNonNull(source);
        Objects.requireNonNull(rule);
        Objects.requireNonNull(basis);
    }
}
