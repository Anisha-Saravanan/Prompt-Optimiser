package org.promptoptimizer.validate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Runs the checks in blueprint cost order and returns structured results. Fail-closed: a check that
 * throws is reported as a failed check, never skipped. Deterministic checks only; this class makes no
 * claim about semantic equivalence.
 */
public final class PreservationValidator {
    private final List<Check> checks;

    public PreservationValidator(List<Check> checks) {
        if (checks == null || checks.isEmpty()) {
            throw new IllegalArgumentException("at least one check is required");
        }
        this.checks = List.copyOf(checks);
    }

    /** All eleven blueprint checks: six implemented, five reported as not run / not applicable with the reason. */
    public static PreservationValidator standard() {
        return new PreservationValidator(List.of(
                new BudgetCheck(),
                new ProtectedSpanCheck(),
                new ExactValueCheck(),
                new RequirementCheck(),
                UnsupportedCheck.notRun(CheckId.IMPORTANT_FACTS,
                        "needs IMPORTANT-unit scores and a fact-entailment verifier (not implemented)"),
                new StructureCheck(),
                UnsupportedCheck.notApplicable(CheckId.CLOSURE,
                        "no dependency graph exists yet (blueprint S4 graph is a later milestone)"),
                UnsupportedCheck.notRun(CheckId.SEMANTIC,
                        "model-based entailment check is not implemented; only lossless operators exist so far"),
                UnsupportedCheck.notApplicable(CheckId.TASK_RISK,
                        "no task-type classification exists yet, so no policy-specific risk rules can be selected"),
                new ProvenanceCheck(),
                UnsupportedCheck.notRun(CheckId.PROBE, "research probe needs an LLM and is never a pass criterion")));
    }

    public ValidationResult validate(ValidationContext ctx) {
        List<CheckResult> results = new ArrayList<>();
        for (Check c : checks) {
            try {
                results.add(c.run(ctx));
            } catch (RuntimeException e) {
                results.add(CheckResult.of(c.id(), List.of(new Finding(c.id(), defaultClass(c.id()),
                        Finding.Severity.ERROR, "check threw " + e.getClass().getSimpleName() + ": " + e.getMessage(),
                        null, List.of(), null))));
            }
        }
        results.sort(Comparator.comparingInt(r -> r.check().number()));
        return new ValidationResult(results);
    }

    private static FailureClass defaultClass(CheckId id) {
        switch (id) {
            case BUDGET: return FailureClass.OVERBUDGET;
            case PROTECTED_SPANS: return FailureClass.SPAN_LOST;
            case EXACT_VALUES: return FailureClass.VALUE_LOST;
            case REQUIREMENTS: return FailureClass.REQ_MISSING;
            case STRUCTURE: return FailureClass.STRUCT_INVALID;
            default: return FailureClass.PROVENANCE;
        }
    }
}
