package org.promptoptimizer.recover;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.promptoptimizer.assemble.AssembledInput;
import org.promptoptimizer.lossless.LosslessPass;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.LosslessResult.PreparedDocument;
import org.promptoptimizer.lossless.LosslessResult.TransformationRecord;
import org.promptoptimizer.token.TokenCounter;
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
 * @param records        one terminal-action record per input unit (empty if nothing is delivered); selected-out units
 *                       have an explicit DROPPED record
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

    /**
     * One step of the decision trail, in order.
     *
     * @param phase     PRIMARY, RESTORE_ROUND_n, FALLBACK_LESS_LOSSY, FALLBACK_LOSSLESS, FALLBACK_ORIGINAL or INFEASIBLE_BUDGET
     * @param outcome   ACCEPTED, REJECTED, ABORTED, RESTORED, SELECTED, RESELECTED or BUILD_FAILED
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

    /**
     * Blueprint 6.5: what the caller needs to know when the budget is below the smallest safe representation found.
     *
     * @param budget          the requested budget B
     * @param minimumTokens   M: tokens of the smallest representation that passed every check except the budget
     * @param deficit         M - B (always positive)
     * @param minimumSource   which plan (or the original) produced M
     * @param minimumText     the smallest safe representation; a caller may use it only knowingly (blueprint option 5)
     * @param composition     piece-wise token estimate of M by unit type, plus SCAFFOLD and OMISSION_MARKER; the parts are
     *                        counted separately, so their sum can differ slightly from {@code minimumTokens}
     * @param exact           whether the counter is the target tokenizer (false for the heuristic counter: M is an upper bound)
     * @param options         the blueprint's options, each marked as available or not in this build
     */
    public record Infeasibility(int budget, int minimumTokens, int deficit, String minimumSource, String minimumText,
                                Map<String, Integer> composition, boolean exact, List<String> options) {
        public Infeasibility {
            composition = Map.copyOf(composition);
            options = List.copyOf(options);
        }
    }

    /** Final status of a recovery run (blueprint 7.2): which rung of the ladder produced the delivered text. */
    public enum RecoveryStatus {
        /** The primary plan passed every mandatory check, possibly after restore-only rounds. */
        ACCEPT,
        /** The primary plan failed; the next less-lossy plan passed. */
        FALLBACK_LESS_LOSSY,
        /** The less-lossy plan failed or does not exist; the lossless-only plan passed. */
        FALLBACK_LOSSLESS,
        /** Nothing passed; the original input is returned as it was, with an explanation. */
        FALLBACK_ORIGINAL,
        /** The original does not fit the budget and no safe candidate does either; nothing is delivered. */
        INFEASIBLE_BUDGET
    }

    /**
     * Limits of the decision loop.
     *
     * @param maxRestoreRounds        restore-only revise rounds, 0..3. After a restore, the standard controller may
     *                                re-run budget selection while forcing restored units to remain.
     * @param rejectLargerThanOriginal if true, a candidate with more tokens than the original input (as it would be
     *                                delivered) is rejected: optimizing must not make the input bigger. Equal is accepted.
     */
    public record RecoveryPolicy(int maxRestoreRounds, boolean rejectLargerThanOriginal) {
        public static final int MAX_SUPPORTED_ROUNDS = 3;

        public RecoveryPolicy {
            if (maxRestoreRounds < 0 || maxRestoreRounds > MAX_SUPPORTED_ROUNDS) {
                throw new IllegalArgumentException("maxRestoreRounds must be 0.." + MAX_SUPPORTED_ROUNDS);
            }
        }

        public static RecoveryPolicy defaults() {
            return new RecoveryPolicy(MAX_SUPPORTED_ROUNDS, true);
        }
    }

    /** A named way of producing a candidate from the prepared documents. The controller knows nothing about operators. */
    public record CandidatePlan(String name, Function<List<PreparedDocument>, LosslessResult> builder) {
        public CandidatePlan {
            Objects.requireNonNull(name);
            Objects.requireNonNull(builder);
        }

        /**
         * The ladder for the operators that exist today. Because every current operator is reversible, the ladder
         * is expressed in operators switched on:
         * <ul>
         *   <li>primary: whitespace normalization + exact-duplicate removal (the only operator that replaces content with a marker);</li>
         *   <li>less lossy: whitespace normalization only;</li>
         *   <li>lossless only: no operator at all (every unit KEPT; only file delimiters are added).</li>
         * </ul>
         * When lossy operators exist, the primary becomes the lossy plan and these rungs move down accordingly.
         */
        public static Ladder lossless(TokenCounter counter) {
            return new Ladder(
                    new CandidatePlan("lossless-pass:whitespace+dedup", d -> new LosslessPass(counter,
                            new LosslessPass.Config(true, true, LosslessPass.Config.defaults().minDuplicateTokens())).run(d)),
                    new CandidatePlan("lossless-pass:whitespace-only", d -> new LosslessPass(counter,
                            new LosslessPass.Config(true, false, LosslessPass.Config.defaults().minDuplicateTokens())).run(d)),
                    new CandidatePlan("lossless-pass:no-operators", d -> new LosslessPass(counter,
                            new LosslessPass.Config(false, false, LosslessPass.Config.defaults().minDuplicateTokens())).run(d)));
        }

        /** The three plans of the ladder. A null fallback plan means that rung does not exist. */
        public record Ladder(CandidatePlan primary, CandidatePlan lessLossy, CandidatePlan losslessOnly) {
            public Ladder {
                Objects.requireNonNull(primary);
            }
        }
    }
}
