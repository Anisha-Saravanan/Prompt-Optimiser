package org.promptoptimizer.model;

import java.util.Objects;

/**
 * A span of canonical text that must be preserved. Offsets are absolute within the document.
 *
 * @param docId  owning document id
 * @param start  inclusive offset
 * @param end    exclusive offset
 * @param kind   what was detected
 * @param level  HARD (never altered) or SOFT
 * @param reason detector rule that fired
 */
public record ProtectedSpan(String docId, int start, int end, SpanKind kind, Level level, String reason) {
    public ProtectedSpan {
        Objects.requireNonNull(docId);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(level);
        if (level != Level.HARD && level != Level.SOFT) {
            throw new IllegalArgumentException("protection level must be HARD or SOFT");
        }
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid span [" + start + "," + end + ")");
        }
    }

    public boolean overlaps(ProtectedSpan o) {
        return docId.equals(o.docId) && start < o.end && o.start < end;
    }
}
