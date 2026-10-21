package org.promptoptimizer.lossless;

import java.util.ArrayList;
import java.util.List;

/**
 * An ordered, non-overlapping list of {@link TextEdit}s over one source text, with the reverse map
 * needed to restore the source exactly. Offsets are relative to the start of the source text.
 */
public final class EditScript {
    /** Half-open range of source offsets. */
    public record Range(int start, int end) {}

    private final int sourceLength;
    private final List<TextEdit> edits;

    public EditScript(int sourceLength, List<TextEdit> edits) {
        if (sourceLength < 0) {
            throw new IllegalArgumentException("negative source length");
        }
        int prevEnd = 0;
        for (TextEdit e : edits) {
            if (e.start() < prevEnd) {
                throw new IllegalArgumentException("edits overlap or are unsorted at " + e.start());
            }
            if (e.end() > sourceLength) {
                throw new IllegalArgumentException("edit exceeds source length");
            }
            prevEnd = e.end();
        }
        this.sourceLength = sourceLength;
        this.edits = List.copyOf(edits);
    }

    public static EditScript empty(int sourceLength) {
        return new EditScript(sourceLength, List.of());
    }

    public List<TextEdit> edits() {
        return edits;
    }

    public boolean isEmpty() {
        return edits.isEmpty();
    }

    public int sourceLength() {
        return sourceLength;
    }

    public int transformedLength() {
        int len = sourceLength;
        for (TextEdit e : edits) {
            len += e.delta();
        }
        return len;
    }

    /** Applies the edits to {@code source}, verifying that it is the text the script was built for. */
    public String apply(String source) {
        if (source.length() != sourceLength) {
            throw new IllegalArgumentException("source length " + source.length() + ", expected " + sourceLength);
        }
        StringBuilder out = new StringBuilder(transformedLength());
        int pos = 0;
        for (TextEdit e : edits) {
            if (!source.startsWith(e.removed(), e.start())) {
                throw new IllegalArgumentException("source does not contain the expected text at " + e.start());
            }
            out.append(source, pos, e.start()).append(e.replacement());
            pos = e.end();
        }
        out.append(source, pos, source.length());
        return out.toString();
    }

    /** Restores the source from the transformed text using only the stored reverse map. */
    public String revert(String transformed) {
        if (transformed.length() != transformedLength()) {
            throw new IllegalArgumentException(
                    "transformed length " + transformed.length() + ", expected " + transformedLength());
        }
        StringBuilder out = new StringBuilder(sourceLength);
        int pos = 0;
        int srcPos = 0;
        for (TextEdit e : edits) {
            int untouched = e.start() - srcPos;
            out.append(transformed, pos, pos + untouched);
            pos += untouched;
            if (!transformed.startsWith(e.replacement(), pos)) {
                throw new IllegalArgumentException("transformed text does not contain the expected replacement");
            }
            pos += e.replacement().length();
            out.append(e.removed());
            srcPos = e.end();
        }
        out.append(transformed, pos, transformed.length());
        return out.toString();
    }

    /**
     * Maps a source offset to the matching offset in the transformed text.
     *
     * @throws IllegalArgumentException if the offset lies strictly inside an edited region
     */
    public int toTransformed(int sourceOffset) {
        if (sourceOffset < 0 || sourceOffset > sourceLength) {
            throw new IllegalArgumentException("offset out of range: " + sourceOffset);
        }
        int delta = 0;
        for (TextEdit e : edits) {
            if (sourceOffset <= e.start()) {
                break;
            }
            if (sourceOffset < e.end()) {
                throw new IllegalArgumentException("offset " + sourceOffset + " is inside an edited region");
            }
            delta += e.delta();
        }
        return sourceOffset + delta;
    }

    /**
     * Source range that produced the character at {@code transformedOffset}: a single character for
     * untouched text, the whole edited range for a replacement character.
     */
    public Range originRange(int transformedOffset) {
        if (transformedOffset < 0 || transformedOffset >= transformedLength()) {
            throw new IllegalArgumentException("offset out of range: " + transformedOffset);
        }
        int delta = 0;
        for (TextEdit e : edits) {
            int tStart = e.start() + delta;
            if (transformedOffset < tStart) {
                break;
            }
            int tEnd = tStart + e.replacement().length();
            if (transformedOffset < tEnd) {
                return new Range(e.start(), e.end());
            }
            delta += e.delta();
        }
        int src = transformedOffset - delta;
        return new Range(src, src + 1);
    }

    /** Distinct edit kinds in order of first appearance. */
    public List<EditKind> kinds() {
        List<EditKind> kinds = new ArrayList<>();
        for (TextEdit e : edits) {
            if (!kinds.contains(e.kind())) {
                kinds.add(e.kind());
            }
        }
        return kinds;
    }
}
