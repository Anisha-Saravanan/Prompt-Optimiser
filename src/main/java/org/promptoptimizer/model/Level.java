package org.promptoptimizer.model;

/** Protection level of a span, or lossiness level of an operation. */
public enum Level {
    /** Must survive byte-identical. Never altered by any operator. */
    HARD,
    /** Should survive; may be merged only with verified duplicates. */
    SOFT,
    /** Operation level: reversible and meaning-preserving. */
    LOSSLESS,
    /** Operation level: near-lossless but not provably equivalent. */
    SOFT_LOSSY,
    /** Operation level: information is removed. */
    LOSSY
}
