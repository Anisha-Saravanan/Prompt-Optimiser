package org.promptoptimizer.task;

import java.util.Objects;
import org.promptoptimizer.model.CanonicalDocument;

/**
 * A half-open character range [start, end) of a canonical document. Every extracted item points
 * back to the exact text it was taken from.
 *
 * @param docId owning document id
 * @param start inclusive offset into canonical text
 * @param end   exclusive offset into canonical text
 */
public record SourceRef(String docId, int start, int end) {
    public SourceRef {
        Objects.requireNonNull(docId);
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("invalid source range [" + start + "," + end + ")");
        }
    }

    public String slice(CanonicalDocument doc) {
        return doc.text().substring(start, end);
    }

    public boolean overlaps(SourceRef o) {
        return docId.equals(o.docId) && start < o.end && o.start < end;
    }
}
