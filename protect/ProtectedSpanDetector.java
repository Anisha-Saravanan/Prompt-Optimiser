package org.promptoptimizer.protect;

import java.util.List;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.ProtectedSpan;

/** Finds content that must survive exactly. Implementations must be deterministic. */
public interface ProtectedSpanDetector {
    /** Returns spans sorted by start, with overlaps merged (the stronger level wins). */
    List<ProtectedSpan> detect(CanonicalDocument doc);
}
