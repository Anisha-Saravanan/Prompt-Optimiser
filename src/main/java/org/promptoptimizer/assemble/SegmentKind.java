package org.promptoptimizer.assemble;

/** What a stretch of assembled output is. */
public enum SegmentKind {
    /** Lossless text of a kept unit. */
    UNIT,
    /** Visible marker standing in for a removed duplicate unit. */
    OMISSION_MARKER,
    /** File delimiters and separators added by the assembler; has no source. */
    SCAFFOLD
}
