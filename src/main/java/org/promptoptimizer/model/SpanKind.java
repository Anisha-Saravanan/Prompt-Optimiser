package org.promptoptimizer.model;

/** Kinds of protected content (blueprint section 5.4). */
public enum SpanKind {
    CONSTRAINT_SENTENCE,
    NEGATION_OR_MODAL,
    NUMBER_WITH_UNIT,
    IDENTIFIER,
    ERROR_TEXT,
    PATH_OR_URL,
    QUOTED_STRING
}
