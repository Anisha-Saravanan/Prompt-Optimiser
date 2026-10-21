package org.promptoptimizer.validate;

import java.util.List;
import java.util.Objects;

/**
 * One concrete reason a check failed (or warned).
 *
 * @param unitIds original unit ids the failure is attributed to; these are the units a restore-only repair
 *                can restore. Empty means the failure cannot be repaired by restoring units (for example an
 *                over-budget output or a broken file header).
 * @param itemId  id of the TaskProfile item involved, or null
 */
public record Finding(CheckId check, FailureClass failureClass, Severity severity, String message, String docId,
                      List<String> unitIds, String itemId) {
    public enum Severity { ERROR, WARNING }

    public Finding {
        Objects.requireNonNull(check);
        Objects.requireNonNull(failureClass);
        Objects.requireNonNull(severity);
        Objects.requireNonNull(message);
        unitIds = List.copyOf(unitIds);
    }

    /** Stable identity of the failure without its message text; used to measure repair progress. */
    public String fingerprint() {
        return check.number() + ":" + failureClass + ":" + (docId == null ? "-" : docId) + ":"
                + String.join(",", unitIds) + ":" + (itemId == null ? "-" : itemId);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
