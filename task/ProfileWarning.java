package org.promptoptimizer.task;

import java.util.Objects;

/**
 * A transparent note about an extraction limitation. Warnings never change the extracted items;
 * they tell downstream stages (and the user) where to look.
 *
 * @param code     machine-readable reason
 * @param severity INFO (FYI) or WARN (needs attention)
 * @param message  human-readable explanation
 * @param source   related text, or null when the warning concerns the whole prompt
 */
public record ProfileWarning(Code code, Severity severity, String message, SourceRef source) {

    public enum Severity { INFO, WARN }

    public enum Code {
        EMPTY_INPUT,
        MISSING_TASK,
        AMBIGUOUS_TASK,
        MULTIPLE_TASK_CANDIDATES,
        EMPTY_SECTION,
        DUPLICATE_ITEM,
        /** Cue-bearing sentence outside a labelled section; kept as candidate, not promoted. */
        UNPROMOTED_CANDIDATE,
        /** Cue words occur only inside code, quotes or block quotes; not treated as instructions. */
        CUE_IN_CODE_OR_QUOTE
    }

    public ProfileWarning {
        Objects.requireNonNull(code);
        Objects.requireNonNull(severity);
        Objects.requireNonNull(message);
    }
}
