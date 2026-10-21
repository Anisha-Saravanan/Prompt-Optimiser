package org.promptoptimizer.recover;

import java.util.List;
import java.util.Objects;
import org.promptoptimizer.assemble.AssembledInput;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.TransformationRecord;
import org.promptoptimizer.validate.ValidationResult;

/**
 * Result of {@link RecoveryController#run}.
 *
 * @param text           the text to deliver; null if and only if status is INFEASIBLE_BUDGET (nothing is delivered)
 * @param tokens         tokens of {@code text} (0 when nothing is delivered)
 * @param assembled      the assembled candidate for ACCEPT and FALLBACK_LESS_LOSSY / FALLBACK_LOSSLESS; null otherwise
 * @param result         the lossless state behind {@code assembled}; null otherwise
 * @param validation     validation of the delivered candidate; null for FALLBACK_ORIGINAL and INFEASIBLE_BUDGET
 *                       (the original is delivered as-is and is not re-validated)
 * @param restoreRounds  restore-only rounds used on the primary plan
 * @param originalTokens tokens of the original input as it would be delivered
 * @param exact          whether the token counts come from the target tokenizer (false: upper-bound estimates)
 * @param infeasibility  non-null if and only if status is INFEASIBLE_BUDGET
 * @param records        exactly one terminal-action record per unit of the delivered text (empty if nothing is delivered)
 * @param warnings       human-readable cautions, including every check that was NOT verified
 */
public record RecoveryOutcome(RecoveryStatus status, String text, int tokens, String tokenizerId, boolean exact,
                              AssembledInput assembled, LosslessResult result, ValidationResult validation,
                              int restoreRounds, int originalTokens, List<AuditEntry> audit,
                              Infeasibility infeasibility, List<TransformationRecord> records, List<String> warnings) {
    public RecoveryOutcome {
        Objects.requireNonNull(status);
        Objects.requireNonNull(tokenizerId);
        audit = List.copyOf(audit);
        records = List.copyOf(records);
        warnings = List.copyOf(warnings);
        if ((status == RecoveryStatus.INFEASIBLE_BUDGET) != (text == null)) {
            throw new IllegalArgumentException("text is null exactly when the status is INFEASIBLE_BUDGET");
        }
        if ((status == RecoveryStatus.INFEASIBLE_BUDGET) != (infeasibility != null)) {
            throw new IllegalArgumentException("infeasibility is present exactly when the status is INFEASIBLE_BUDGET");
        }
    }

    public boolean deliverable() {
        return text != null;
    }

    /** Tokens added (positive) or saved (negative) relative to the original input; 0 when nothing is delivered. */
    public int netTokenChange() {
        return deliverable() ? tokens - originalTokens : 0;
    }
}
