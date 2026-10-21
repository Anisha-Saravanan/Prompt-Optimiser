package org.promptoptimizer.recover;

import java.util.List;
import java.util.Objects;

/**
 * One step of the decision trail, in order.
 *
 * @param phase     PRIMARY, RESTORE_ROUND_n, FALLBACK_LESS_LOSSY, FALLBACK_LOSSLESS, FALLBACK_ORIGINAL or INFEASIBLE_BUDGET
 * @param outcome   ACCEPTED, REJECTED, ABORTED, RESTORED or BUILD_FAILED
 * @param tokens    tokens of the candidate examined at this step, or -1 if none could be built
 * @param failing   fingerprints of the validation errors found at this step
 * @param restored  unit ids restored by this step (RESTORED entries only)
 */
public record AuditEntry(int step, String phase, String plan, String outcome, int tokens, String validation,
                         List<String> failing, List<String> restored, String detail) {
    public AuditEntry {
        Objects.requireNonNull(phase);
        Objects.requireNonNull(plan);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(validation);
        Objects.requireNonNull(detail);
        failing = List.copyOf(failing);
        restored = List.copyOf(restored);
    }
}
