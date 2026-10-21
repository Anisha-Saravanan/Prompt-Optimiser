package org.promptoptimizer.model;

import java.util.Objects;

/**
 * An addressable, contiguous slice of a canonical document.
 * Units of one document, in order, must concatenate to exactly the document text
 * (the partition property; see {@code Segmenter.verifyPartition}).
 *
 * @param docId   owning document id
 * @param ordinal position within the document, starting at 0
 * @param start   inclusive offset into canonical text
 * @param end     exclusive offset into canonical text
 * @param type    unit type
 * @param tokens  token count under the counter used (see TokenCount.exact)
 */
public record InformationUnit(String docId, int ordinal, int start, int end, UnitType type, int tokens) {
    public InformationUnit {
        Objects.requireNonNull(docId);
        Objects.requireNonNull(type);
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("invalid span [" + start + "," + end + ")");
        }
    }

    public String id() {
        return docId + "#" + ordinal;
    }

    public String slice(CanonicalDocument doc) {
        return doc.text().substring(start, end);
    }
}
