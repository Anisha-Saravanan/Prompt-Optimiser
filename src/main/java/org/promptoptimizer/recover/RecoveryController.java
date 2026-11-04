package org.promptoptimizer.recover;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.promptoptimizer.assemble.AssembledInput;
import org.promptoptimizer.assemble.Assembler;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.protect.ProtectedSpanDetector;
import org.promptoptimizer.task.TaskProfile;
import org.promptoptimizer.token.TokenCounter;
import org.promptoptimizer.validate.CheckResult;
import org.promptoptimizer.validate.FailureClass;
import org.promptoptimizer.validate.Finding;
import org.promptoptimizer.validate.PreservationValidator;
import org.promptoptimizer.validate.ValidationContext;
import org.promptoptimizer.validate.ValidationResult;

/**
 * Blueprint stage S12 (decision and recovery), deterministic part.
 *
 * <p>Flow: build the primary candidate, assemble, validate. If every mandatory check passes and the candidate is not
 * larger than the original: ACCEPT. Otherwise, if every error is repairable by restoring units, restore them verbatim
 * from the stored reverse information (restore-only, at most {@link RecoveryPolicy#maxRestoreRounds()} rounds, never a
 * new transformation), re-assemble and re-validate. A round must strictly reduce the set of failing checks (the new
 * error fingerprints must be a proper subset of the previous ones) or the loop aborts, so it cannot oscillate. When
 * repair is impossible or exhausted, the fallback ladder runs: less-lossy plan, lossless-only plan, then the original
 * input. If the original exceeds the budget, nothing is delivered: INFEASIBLE_BUDGET with the smallest safe
 * representation found.
 *
 * <p>Failures that restoring cannot cure (OVERBUDGET, PROVENANCE, errors that name no unit) go straight to the ladder.
 * Dropping low-density units to meet a budget and the blueprint's round 3 (re-run S8-S9) are not implemented because
 * scoring and budget allocation do not exist yet. There is no dependency graph, so the "closure" of a restored unit is
 * the unit itself.
 *
 * <p>Guarantee: a candidate is delivered only if the deterministic validator passed it. That says nothing about
 * semantic equivalence; the outcome lists every check that was not verified.
 */
public final class RecoveryController {
    /** Failure classes a restore can cure when the finding names units (blueprint 7.2 repair table). */
    private static final Set<FailureClass> RESTORABLE = EnumSet.of(FailureClass.SPAN_LOST, FailureClass.VALUE_LOST,
            FailureClass.REQ_MISSING, FailureClass.FACT_LOST, FailureClass.STRUCT_INVALID, FailureClass.DANGLING_REF,
            FailureClass.SEMANTIC_DRIFT, FailureClass.TASK_RISK);

    private final PreservationValidator validator;
    private final TokenCounter counter;
    private final ProtectedSpanDetector detector;
    private final CandidatePlan.Ladder ladder;
    private final RecoveryPolicy policy;
    private final Assembler assembler;

    public RecoveryController(PreservationValidator validator, TokenCounter counter, ProtectedSpanDetector detector,
                              CandidatePlan.Ladder ladder, RecoveryPolicy policy) {
        this.validator = Objects.requireNonNull(validator);
        this.counter = Objects.requireNonNull(counter);
        this.detector = Objects.requireNonNull(detector);
        this.ladder = Objects.requireNonNull(ladder);
        this.policy = Objects.requireNonNull(policy);
        this.assembler = new Assembler(counter);
    }

    /** Standard validator, the lossless ladder for the given counter and default policy. */
    public static RecoveryController standard(TokenCounter counter, ProtectedSpanDetector detector) {
        return new RecoveryController(PreservationValidator.standard(), counter, detector, CandidatePlan.lossless(counter),
                RecoveryPolicy.defaults());
    }

    /**
     * @param profile task profile of the PROMPT document, or null
     * @param budget  token budget, or null for none
     * @throws IllegalArgumentException for invalid inputs (duplicate ids, several prompts, foreign profile, negative
     *                                  budget). Invalid input is never turned into a fallback.
     */
    public RecoveryOutcome run(List<PreparedDocument> docs, TaskProfile profile, Integer budget) {
        Objects.requireNonNull(docs);
        // Argument validation only: the throwaway empty candidate is never examined.
        new ValidationContext(docs, profile, budget, counter, detector,
                new AssembledInput("", 0, 0, 0, counter.id(), List.of(), List.of()));
        return new Run(List.copyOf(docs), profile, budget).execute();
    }

    private record Evaluation(String plan, LosslessResult result, AssembledInput assembled, ValidationResult validation,
                              boolean larger) {
        boolean accepted() {
            return validation.mandatoryPass() && !larger;
        }

        /** True if the only errors are budget errors: a representation that is safe apart from its size. */
        boolean safeExceptBudget() {
            return validation.errors().stream().allMatch(e -> e.failureClass() == FailureClass.OVERBUDGET);
        }
    }

    private record Repair(Set<String> unitIds, String reason, String refusal) {
        static Repair yes(Set<String> ids, String reason) {
            return new Repair(ids, reason, null);
        }

        static Repair no(String refusal) {
            return new Repair(Set.of(), null, refusal);
        }
    }

    /** State of one run. */
    private final class Run {
        final List<PreparedDocument> docs;
        final TaskProfile profile;
        final Integer budget;
        final String originalText;
        final int originalTokens;
        final boolean exact;
        final List<AuditEntry> audit = new ArrayList<>();
        final List<Evaluation> evaluations = new ArrayList<>();
        int restoreRounds;

        Run(List<PreparedDocument> docs, TaskProfile profile, Integer budget) {
            this.docs = docs;
            this.profile = profile;
            this.budget = budget;
            this.originalText = OriginalDelivery.text(docs);
            var tc = counter.count(originalText);
            this.originalTokens = tc.tokens();
            this.exact = tc.exact();
        }

        RecoveryOutcome execute() {
            LosslessResult current = build(ladder.primary(), "PRIMARY");
            Set<String> previous = null;
            int round = 0;
            while (current != null) {
                String phase = round == 0 ? "PRIMARY" : "RESTORE_ROUND_" + round;
                Evaluation ev = evaluate(current, ladder.primary().name(), phase);
                if (ev == null) {
                    break;
                }
                Set<String> failing = ev.validation().errorFingerprints();
                if (ev.accepted()) {
                    note(phase, ev.plan(), "ACCEPTED", ev, round == 0 ? "all mandatory checks passed"
                            : "all mandatory checks passed after " + round + " restore round(s)");
                    restoreRounds = round;
                    return delivered(RecoveryStatus.ACCEPT, ev);
                }
                if (ev.larger()) {
                    note(phase, ev.plan(), "REJECTED", ev, "candidate has " + ev.assembled().tokens()
                            + " tokens, more than the original's " + originalTokens
                            + (ev.validation().mandatoryPass() ? "" : "; validation also failed: " + ev.validation().summary()));
                    break;
                }
                if (round > 0 && !isProperSubset(failing, previous)) {
                    note(phase, ev.plan(), "ABORTED", ev, "no progress: the failing set did not strictly shrink ("
                            + previous.size() + " -> " + failing.size() + " errors, or new errors appeared)");
                    break;
                }
                Repair repair = analyse(ev.validation(), current);
                if (repair.refusal() != null) {
                    note(phase, ev.plan(), "REJECTED", ev, "not repairable by restoring units: " + repair.refusal());
                    break;
                }
                if (round >= policy.maxRestoreRounds()) {
                    note(phase, ev.plan(), "REJECTED", ev, round == 0
                            ? "repairable, but restore rounds are disabled (maxRestoreRounds=0)"
                            : "restore rounds exhausted (" + round + ")");
                    break;
                }
                note(phase, ev.plan(), "REJECTED", ev, "repairable: restoring " + repair.unitIds().size() + " unit(s) ["
                        + repair.reason() + "]");
                round++;
                try {
                    current = current.withRestored(repair.unitIds(), round, repair.reason());
                } catch (RuntimeException e) {
                    audit.add(new AuditEntry(audit.size() + 1, "RESTORE_ROUND_" + round, ev.plan(), "ABORTED", -1, "-",
                            List.of(), List.copyOf(repair.unitIds()), "restore failed: " + e));
                    break;
                }
                audit.add(new AuditEntry(audit.size() + 1, "RESTORE_ROUND_" + round, ev.plan(), "RESTORED", -1, "-",
                        List.of(), List.copyOf(repair.unitIds()), "restored verbatim from stored reverse information"));
                previous = failing;
            }
            return fallbackLadder();
        }

        // ---- fallback ladder -------------------------------------------------------------------------------------

        RecoveryOutcome fallbackLadder() {
            RecoveryOutcome out = tryRung(RecoveryStatus.FALLBACK_LESS_LOSSY, ladder.lessLossy());
            if (out != null) {
                return out;
            }
            out = tryRung(RecoveryStatus.FALLBACK_LOSSLESS, ladder.losslessOnly());
            if (out != null) {
                return out;
            }
            return originalRung();
        }

        RecoveryOutcome tryRung(RecoveryStatus status, CandidatePlan plan) {
            String phase = status.name();
            if (plan == null) {
                audit.add(new AuditEntry(audit.size() + 1, phase, "-", "ABORTED", -1, "-", List.of(), List.of(),
                        "rung not configured"));
                return null;
            }
            LosslessResult result = build(plan, phase);
            if (result == null) {
                return null;
            }
            Evaluation ev = evaluate(result, plan.name(), phase);
            if (ev == null) {
                return null;
            }
            if (ev.accepted()) {
                note(phase, plan.name(), "ACCEPTED", ev, "all mandatory checks passed");
                return delivered(status, ev);
            }
            note(phase, plan.name(), "REJECTED", ev, ev.larger()
                    ? "candidate has " + ev.assembled().tokens() + " tokens, more than the original's " + originalTokens
                    : "validation failed: " + ev.validation().summary());
            return null;
        }

        RecoveryOutcome originalRung() {
            if (budget != null && originalTokens > budget) {
                return infeasible();
            }
            List<String> warnings = new ArrayList<>();
            warnings.add("The optimized candidates were rejected, so the ORIGINAL input is returned unchanged. It was not"
                    + " re-validated because no transformation was applied.");
            for (AuditEntry a : audit) {
                if (!a.outcome().equals("ACCEPTED")) {
                    warnings.add(a.phase() + " (" + a.plan() + "): " + a.outcome() + " - " + a.detail());
                }
            }
            audit.add(new AuditEntry(audit.size() + 1, "FALLBACK_ORIGINAL", "original", "ACCEPTED", originalTokens, "-",
                    List.of(), List.of(), "original input returned"));
            return new RecoveryOutcome(RecoveryStatus.FALLBACK_ORIGINAL, originalText, originalTokens, counter.id(), exact,
                    null, null, null, restoreRounds, originalTokens, audit,
                    null, OriginalDelivery.records(docs, counter, "FALLBACK_ORIGINAL"), warnings);
        }

        RecoveryOutcome infeasible() {
            Evaluation best = null;
            for (Evaluation e : evaluations) {
                if (e.safeExceptBudget() && (best == null || e.assembled().tokens() < best.assembled().tokens())) {
                    best = e;
                }
            }
            int m;
            String source;
            String text;
            Map<String, Integer> composition;
            if (best == null || originalTokens <= best.assembled().tokens()) {
                m = originalTokens;
                source = "original input";
                text = originalText;
                composition = OriginalDelivery.composition(docs, counter);
            } else {
                m = best.assembled().tokens();
                source = best.plan();
                text = best.assembled().text();
                composition = OriginalDelivery.composition(best.assembled(), docs, counter);
            }
            int deficit = m - budget;
            if (deficit <= 0) {
                throw new IllegalStateException("internal error: minimum safe representation (" + m
                        + " tokens) fits the budget (" + budget + ") but was not accepted");
            }
            List<String> options = List.of(
                    "1. Raise the budget to at least " + m + " tokens (available).",
                    "2. Waive named units explicitly; each waiver would be logged and treated as waived, not missing"
                            + " (NOT AVAILABLE in this build: no waiver mechanism).",
                    "3. Split into sub-requests when the task decomposes (NOT AVAILABLE: no decomposition support).",
                    "4. Change representation, for example CSV to a query tool or a repository to retrieval"
                            + " (NOT AVAILABLE: no such strategies exist yet).",
                    "5. Use the least-compressed safe plan at " + m + " tokens, which exceeds the budget by " + deficit
                            + " (available: see minimumText; use only knowingly).");
            Infeasibility inf = new Infeasibility(budget, m, deficit, source, text, composition, exact, options);
            audit.add(new AuditEntry(audit.size() + 1, "INFEASIBLE_BUDGET", source, "REJECTED", m, "-", List.of(), List.of(),
                    "budget " + budget + " is below the minimum safe representation " + m + " (deficit " + deficit
                            + "); nothing is delivered, nothing is truncated"));
            List<String> warnings = List.of("INFEASIBLE_BUDGET: no representation that preserves the protected content fits "
                    + budget + " tokens; the smallest safe one needs " + m + (exact ? "" : " (upper-bound estimate)") + ".");
            return new RecoveryOutcome(RecoveryStatus.INFEASIBLE_BUDGET, null, 0, counter.id(), exact, null, null, null,
                    restoreRounds, originalTokens, audit, inf, List.of(), warnings);
        }

        // ---- building blocks -------------------------------------------------------------------------------------

        RecoveryOutcome delivered(RecoveryStatus status, Evaluation ev) {
            List<String> warnings = new ArrayList<>();
            if (status != RecoveryStatus.ACCEPT) {
                warnings.add("The primary plan was rejected; delivering the " + status + " candidate (" + ev.plan() + ").");
            }
            List<CheckResult> unverified = ev.validation().notVerified();
            if (!unverified.isEmpty()) {
                warnings.add("Accepted on deterministic checks only. NOT verified: " + unverified.stream()
                        .map(c -> c.check().number() + " " + c.check().title() + " [" + c.status() + "]")
                        .collect(Collectors.joining("; ")) + ".");
            }
            AssembledInput a = ev.assembled();
            return new RecoveryOutcome(status, a.text(), a.tokens(), a.tokenizerId(), exact, a, ev.result(),
                    ev.validation(), restoreRounds, originalTokens, audit, null, a.records(), warnings);
        }

        /** Builds a candidate; on failure records it and returns null. */
        LosslessResult build(CandidatePlan plan, String phase) {
            try {
                return plan.builder().apply(docs);
            } catch (RuntimeException e) {
                audit.add(new AuditEntry(audit.size() + 1, phase, plan.name(), "BUILD_FAILED", -1, "-", List.of(), List.of(),
                        "plan threw " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                return null;
            }
        }

        /** Assembles and validates; on failure records it and returns null. The evaluation is remembered. */
        Evaluation evaluate(LosslessResult result, String plan, String phase) {
            try {
                AssembledInput a = assembler.assemble(result);
                ValidationResult v = validator.validate(new ValidationContext(docs, profile, budget, counter, detector, a));
                boolean larger = policy.rejectLargerThanOriginal() && a.tokens() > originalTokens;
                Evaluation ev = new Evaluation(plan, result, a, v, larger);
                evaluations.add(ev);
                return ev;
            } catch (RuntimeException e) {
                audit.add(new AuditEntry(audit.size() + 1, phase, plan, "BUILD_FAILED", -1, "-", List.of(), List.of(),
                        "assembly or validation threw " + e.getClass().getSimpleName() + ": " + e.getMessage()));
                return null;
            }
        }

        void note(String phase, String plan, String outcome, Evaluation ev, String detail) {
            audit.add(new AuditEntry(audit.size() + 1, phase, plan, outcome, ev.assembled().tokens(),
                    ev.validation().summary(), List.copyOf(ev.validation().errorFingerprints()), List.of(), detail));
        }

        /** Decides whether restoring units can cure every error, and which units to restore. */
        Repair analyse(ValidationResult v, LosslessResult current) {
            List<Finding> errors = v.errors();
            if (errors.isEmpty()) {
                return Repair.no("a check failed without a finding that names a unit");
            }
            Set<String> ids = new LinkedHashSet<>();
            Set<FailureClass> classes = EnumSet.noneOf(FailureClass.class);
            for (Finding e : errors) {
                if (!RESTORABLE.contains(e.failureClass())) {
                    return Repair.no(e.failureClass() + " cannot be cured by restoring units: " + e.message());
                }
                if (e.unitIds().isEmpty()) {
                    return Repair.no(e.failureClass() + " names no unit to restore: " + e.message());
                }
                ids.addAll(e.unitIds());
                classes.add(e.failureClass());
            }
            Set<String> effective = new LinkedHashSet<>();
            for (String id : ids) {
                try {
                    if (!LosslessResult.isOriginal(current.state(id))) {
                        effective.add(id);
                    }
                } catch (IllegalArgumentException ex) {
                    return Repair.no("finding names unknown unit " + id);
                }
            }
            if (effective.isEmpty()) {
                return Repair.no("every named unit is already in its original form, so restoring changes nothing");
            }
            return Repair.yes(effective, classes.stream().map(Enum::name).sorted().collect(Collectors.joining("+")));
        }
    }

    private static boolean isProperSubset(Set<String> now, Set<String> before) {
        return before.containsAll(now) && now.size() < before.size();
    }
}
