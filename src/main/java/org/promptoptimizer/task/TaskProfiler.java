package org.promptoptimizer.task;

import org.promptoptimizer.model.CanonicalDocument;

/** Extracts a {@link TaskProfile} from a canonical prompt. */
public interface TaskProfiler {
    TaskProfile profile(CanonicalDocument prompt);
}
