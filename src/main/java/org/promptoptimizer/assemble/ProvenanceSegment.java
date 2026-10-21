package org.promptoptimizer.assemble;

import java.util.Objects;
import org.promptoptimizer.lossless.EditScript;

/**
 * Maps {@code [outStart, outEnd)} of the assembled text to its source. For UNIT segments
 * {@code [origStart, origEnd)} is the unit's range in the original document and {@code script} is its
 * reverse map; for OMISSION_MARKER segments the range is the removed unit; SCAFFOLD segments have
 * no source (docId null, range -1).
 */
public record ProvenanceSegment(int outStart, int outEnd, SegmentKind kind, String docId, int origStart, int origEnd,
                                EditScript script) {
    public ProvenanceSegment {
        Objects.requireNonNull(kind);
        if (outStart < 0 || outEnd <= outStart) {
            throw new IllegalArgumentException("invalid output range [" + outStart + "," + outEnd + ")");
        }
        if (kind == SegmentKind.SCAFFOLD) {
            if (docId != null || script != null) {
                throw new IllegalArgumentException("scaffold has no source");
            }
        } else if (docId == null || origStart < 0 || origEnd < origStart) {
            throw new IllegalArgumentException(kind + " segment needs a source range");
        }
        if (kind == SegmentKind.UNIT && script == null) {
            throw new IllegalArgumentException("UNIT segment needs its edit script");
        }
    }
}
