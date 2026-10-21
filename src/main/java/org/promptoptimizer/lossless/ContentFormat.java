package org.promptoptimizer.lossless;

/**
 * Declared format of a document. The caller states it because no file-type detection exists yet
 * (blueprint S0/S1 extractors are a later milestone). It decides which lossless operations are
 * allowed: whitespace and line structure carry meaning in code, logs and structured data, so those
 * formats are never touched by this milestone.
 */
public enum ContentFormat {
    PLAIN_TEXT(true),
    MARKDOWN(true),
    SOURCE_CODE(false),
    LOG(false),
    STRUCTURED_DATA(false);

    private final boolean prose;

    ContentFormat(boolean prose) {
        this.prose = prose;
    }

    /** True if whitespace normalization and exact-duplicate removal may be applied (outside fenced blocks). */
    public boolean isProse() {
        return prose;
    }
}
