package org.promptoptimizer.task;

import org.promptoptimizer.model.CanonicalDocument;

/** Extracts a {@link TaskProfile} from a canonical prompt. Implementations must be deterministic. */
public interface TaskProfiler {
    TaskProfile profile(CanonicalDocument prompt);
}
