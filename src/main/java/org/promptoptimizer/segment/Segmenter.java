package org.promptoptimizer.segment;

import java.util.List;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.token.TokenCounter;

/** Splits a canonical document into addressable units that partition its text exactly. */
public interface Segmenter {
    List<InformationUnit> segment(CanonicalDocument doc, TokenCounter counter);

    /**
     * Verifies the partition property: units are ordinal-sequential, contiguous, start at 0 and
     * end at the document length, so concatenating their slices reproduces the text.
     *
     * @throws IllegalStateException describing the first violation
     */
    static void verifyPartition(CanonicalDocument doc, List<InformationUnit> units) {
        int expectedStart = 0;
        int expectedOrdinal = 0;
        for (InformationUnit u : units) {
            if (!u.docId().equals(doc.id())) {
                throw new IllegalStateException("unit " + u.id() + " belongs to another document");
            }
            if (u.ordinal() != expectedOrdinal) {
                throw new IllegalStateException("unit ordinal " + u.ordinal() + ", expected " + expectedOrdinal);
            }
            if (u.start() != expectedStart) {
                throw new IllegalStateException(
                        "gap or overlap before unit " + u.id() + ": starts at " + u.start() + ", expected " + expectedStart);
            }
            if (u.end() <= u.start()) {
                throw new IllegalStateException("empty unit " + u.id());
            }
            expectedStart = u.end();
            expectedOrdinal++;
        }
        if (expectedStart != doc.text().length()) {
            throw new IllegalStateException(
                    "units end at " + expectedStart + " but document length is " + doc.text().length());
        }
    }
}
