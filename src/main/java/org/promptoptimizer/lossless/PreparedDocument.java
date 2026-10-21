package org.promptoptimizer.lossless;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.model.Level;
import org.promptoptimizer.model.ProtectedSpan;
import org.promptoptimizer.segment.Segmenter;

/**
 * A canonical document together with its segmentation and protected spans: the input of the
 * lossless pass. The constructor verifies the partition property and that every span belongs to
 * the document and lies within it.
 */
public record PreparedDocument(CanonicalDocument doc, DocRole role, ContentFormat format,
                               List<InformationUnit> units, List<ProtectedSpan> spans) {
    public PreparedDocument {
        Objects.requireNonNull(doc);
        Objects.requireNonNull(role);
        Objects.requireNonNull(format);
        units = List.copyOf(units);
        spans = List.copyOf(spans);
        Segmenter.verifyPartition(doc, units);
        if (doc.text().isEmpty() && !units.isEmpty()) {
            throw new IllegalArgumentException("empty document must have no units");
        }
        for (ProtectedSpan s : spans) {
            if (!s.docId().equals(doc.id())) {
                throw new IllegalArgumentException("span belongs to another document");
            }
            if (s.end() > doc.text().length()) {
                throw new IllegalArgumentException("span exceeds document length");
            }
        }
    }

    public List<ProtectedSpan> hardSpans() {
        List<ProtectedSpan> hard = new ArrayList<>();
        for (ProtectedSpan s : spans) {
            if (s.level() == Level.HARD) {
                hard.add(s);
            }
        }
        return hard;
    }
}
