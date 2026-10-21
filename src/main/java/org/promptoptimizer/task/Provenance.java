package org.promptoptimizer.task;

import java.util.Objects;

/**
 * Evidence for one extracted item: where it came from and how it was identified.
 *
 * @param source the quoted text range
 * @param rule   stable identifier for the extraction method
 * @param basis  classification evidence type
 */
public record Provenance(SourceRef source, String rule, Basis basis) {

    public enum Basis {
        /** Classification returned by the configured language model. */
        LLM_CLASSIFICATION,
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
