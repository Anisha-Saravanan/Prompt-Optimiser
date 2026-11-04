package org.promptoptimizer.lossless;

/**
 * The visible marker that stands in for a removed exact-duplicate unit (blueprint S10: omissions
 * must be visible so the model knows content is missing). Lives here because the lossless pass has
 * to know the marker's token cost before deciding that a removal is worth it.
 */
public final class OmissionMarker {
    public static final String PREFIX = "[omitted:";

    private OmissionMarker() {}

    /** Marker line plus the blank line that separates it from the next block. */
    public static String exactRepeat(String survivorDocId, int survivorOrdinal) {
        return PREFIX + " exact repeat of block " + (survivorOrdinal + 1) + " of " + survivorDocId + "]\n\n";
    }
}
