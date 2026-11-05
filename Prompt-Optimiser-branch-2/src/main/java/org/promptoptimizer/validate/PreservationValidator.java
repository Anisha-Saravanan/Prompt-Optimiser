package org.promptoptimizer.validate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Pattern;
import org.promptoptimizer.lossless.LosslessResult.PreparedDocument;
import org.promptoptimizer.model.Model.Level;
import org.promptoptimizer.model.Model.ProtectedSpan;
import org.promptoptimizer.model.Model.SpanKind;
import org.promptoptimizer.optimize.DependencyGraph;
import org.promptoptimizer.token.TokenCounter.TokenCount;
import org.promptoptimizer.validate.ValidationResult.Check;
import org.promptoptimizer.validate.ValidationResult.CheckId;
import org.promptoptimizer.validate.ValidationResult.CheckResult;
import org.promptoptimizer.validate.ValidationResult.FailureClass;
import org.promptoptimizer.validate.ValidationResult.Finding;
import org.promptoptimizer.validate.ValidationResult.ValidationContext;

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

    /** All eleven blueprint checks; unsupported model-based checks remain explicitly marked NOT_RUN. */
    public static PreservationValidator standard() {
        return new PreservationValidator(List.of(
                new BudgetCheck(),
                new ProtectedSpanCheck(),
                new ExactValueCheck(),
                new RequirementCheck(),
                UnsupportedCheck.notRun(CheckId.IMPORTANT_FACTS,
                        "needs calibrated IMPORTANT-unit scores and a fact-entailment verifier"),
                new StructureCheck(),
                new DependencyCheck(),
                UnsupportedCheck.notRun(CheckId.SEMANTIC,
                        "deterministic lexical selection does not establish semantic entailment"),
                new TaskRiskCheck(),
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
            case TASK_RISK: return FailureClass.TASK_RISK;
            case STRUCTURE: return FailureClass.STRUCT_INVALID;
            default: return FailureClass.PROVENANCE;
        }
    }

    /** Check 1: exact recount of the candidate with the context's counter must not exceed the budget. */
    public static final class BudgetCheck implements Check {
        @Override
        public CheckId id() {
            return CheckId.BUDGET;
        }

        @Override
        public CheckResult run(ValidationContext ctx) {
            if (ctx.budget() == null) {
                return CheckResult.notApplicable(id(), "no budget supplied");
            }
            TokenCount tc = ctx.counter().count(ctx.candidate().text());
            if (tc.tokens() <= ctx.budget()) {
                return CheckResult.of(id(), List.of());
            }
            String msg = "output needs " + tc.tokens() + " tokens under '" + tc.tokenizerId() + "'"
                    + (tc.exact() ? "" : " (upper-bound estimate, not the target tokenizer)")
                    + " but the budget is " + ctx.budget() + "; deficit " + (tc.tokens() - ctx.budget());
            return CheckResult.of(id(), List.of(new Finding(id(), FailureClass.OVERBUDGET, Finding.Severity.ERROR, msg,
                    null, List.of(), null)));
        }
    }

    /**
     * Check 3: numbers, identifiers, paths, error strings and quoted strings re-extracted from the original
     * must appear unchanged, whatever their protection level (HARD or SOFT). Blueprint 7.1 says "from
     * ESSENTIAL and hard-keep units"; no importance model exists yet, so every unit is treated as essential.
     */
    public static final class ExactValueCheck implements Check {
        static final Set<SpanKind> VALUE_KINDS = EnumSet.of(SpanKind.NUMBER_WITH_UNIT, SpanKind.IDENTIFIER,
                SpanKind.PATH_OR_URL, SpanKind.ERROR_TEXT, SpanKind.QUOTED_STRING);

        @Override
        public CheckId id() {
            return CheckId.EXACT_VALUES;
        }

        @Override
        public CheckResult run(ValidationContext ctx) {
            List<ProtectedSpan> values = SpanCarrier.originalSpans(ctx).stream()
                    .filter(s -> VALUE_KINDS.contains(s.kind())).collect(Collectors.toList());
            return CheckResult.of(id(), SpanCarrier.verify(OutputIndex.build(ctx), values, id(), FailureClass.VALUE_LOST,
                    "exact value"));
        }
    }

    /**
     * Check 2: every HARD span of the original (supplied spans plus a fresh detector run) is byte-identical in
     * the output, or, for a removed duplicate, contained in the surviving unit (blueprint 5.4 duplicate rule).
     */
    public static final class ProtectedSpanCheck implements Check {
        @Override
        public CheckId id() {
            return CheckId.PROTECTED_SPANS;
        }

        @Override
        public CheckResult run(ValidationContext ctx) {
            List<ProtectedSpan> hard = SpanCarrier.originalSpans(ctx).stream()
                    .filter(s -> s.level() == Level.HARD).collect(Collectors.toList());
            return CheckResult.of(id(), SpanCarrier.verify(OutputIndex.build(ctx), hard, id(), FailureClass.SPAN_LOST,
                    "HARD span"));
        }
    }

    /** Check 7: a kept unit must not refer to a required unit that selection omitted. */
    public static final class DependencyCheck implements Check {
        @Override
        public CheckId id() {
            return CheckId.CLOSURE;
        }

        @Override
        public CheckResult run(ValidationContext ctx) {
            OutputIndex index = OutputIndex.build(ctx);
            List<Finding> findings = new ArrayList<>();
            for (DependencyGraph.Edge edge : DependencyGraph.infer(ctx.originals()).edges()) {
                OutputIndex.Slot source = index.byId.get(edge.unitId());
                OutputIndex.Slot required = index.byId.get(edge.requiredUnitId());
                if (source == null || required == null || !isCarried(index, source)
                        || isCarried(index, required)) {
                    continue;
                }
                findings.add(new Finding(id(), FailureClass.DANGLING_REF, Finding.Severity.ERROR,
                        "unit " + source.id() + " requires omitted unit " + required.id() + " (" + edge.type() + ")",
                        source.unit.docId(), List.of(source.id(), required.id()), null));
            }
            return CheckResult.of(id(), findings);
        }

        private static boolean isCarried(OutputIndex index, OutputIndex.Slot slot) {
            var segment = slot.first();
            if (segment == null) {
                return false;
            }
            if (segment.kind() == org.promptoptimizer.assemble.AssembledInput.SegmentKind.UNIT) {
                return true;
            }
            OutputIndex.Slot survivor = index.survivorOf(segment);
            return survivor != null && survivor.first() != null
                    && survivor.first().kind() == org.promptoptimizer.assemble.AssembledInput.SegmentKind.UNIT;
        }
    }

    /**
     * Check 9's deterministic high-risk subset: tasks requiring all CSV rows or order-sensitive log evidence
     * must retain every original information unit of the corresponding file type. Other task risks remain
     * unverified rather than being inferred from a broad or opaque classifier.
     */
    public static final class TaskRiskCheck implements Check {
        private static final Pattern ALL_ROWS = Pattern.compile(
                "\\b(?:all|every|each|entire|full)\\s+(?:csv\\s+)?rows?\\b|\\brow[- ]by[- ]row\\b"
                        + "|\\bpreserve all records\\b|\\bno rows? (?:may|should) be (?:omitted|dropped)\\b");
        private static final Pattern ORDER_SENSITIVE_LOG = Pattern.compile(
                "\\b(?:timing|chronolog(?:y|ical)?|event order|timestamp(?:s)?|time sequence|race condition)\\b");

        @Override
        public CheckId id() {
            return CheckId.TASK_RISK;
        }

        @Override
        public CheckResult run(ValidationContext ctx) {
            if (ctx.profile() == null || ctx.profile().taskStatement().isEmpty()) {
                return CheckResult.notApplicable(id(), "no task statement is available for risk classification");
            }
            String task = taskText(ctx).toLowerCase(Locale.ROOT);
            boolean preserveCsvRows = ALL_ROWS.matcher(task).find();
            boolean preserveLogOrder = ORDER_SENSITIVE_LOG.matcher(task).find();
            if (!preserveCsvRows && !preserveLogOrder) {
                return CheckResult.notApplicable(id(), "no supported full-row or order-sensitive-log risk cue was found");
            }

            OutputIndex index = OutputIndex.build(ctx);
            List<Finding> findings = new ArrayList<>();
            boolean applicable = false;
            for (PreparedDocument document : ctx.originals()) {
                String name = document.doc().id().toLowerCase(Locale.ROOT);
                boolean csv = preserveCsvRows && name.endsWith(".csv");
                boolean log = preserveLogOrder && name.endsWith(".log");
                if (!csv && !log) {
                    continue;
                }
                applicable = true;
                List<String> missingUnits = new ArrayList<>();
                for (OutputIndex.Slot slot : index.slots) {
                    if (!slot.unit.docId().equals(document.doc().id())) {
                        continue;
                    }
                    boolean carried = slot.segments.stream().anyMatch(segment ->
                            segment.kind() == org.promptoptimizer.assemble.AssembledInput.SegmentKind.UNIT
                                    && index.maps(slot, segment));
                    if (!carried) {
                        missingUnits.add(slot.id());
                    }
                }
                if (!missingUnits.isEmpty()) {
                    findings.add(new Finding(id(), FailureClass.TASK_RISK, Finding.Severity.ERROR,
                            "task requires complete " + (csv ? "CSV rows" : "log order")
                                    + " but " + missingUnits.size() + " source unit(s) are omitted",
                            document.doc().id(), missingUnits, null));
                }
            }
            if (!applicable) {
                return CheckResult.notApplicable(id(), "no CSV or log attachment matches the detected risk cue");
            }
            return CheckResult.of(id(), findings);
        }

        private static String taskText(ValidationContext ctx) {
            List<String> parts = new ArrayList<>();
            ctx.profile().taskStatement().ifPresent(item -> parts.add(item.text()));
            ctx.profile().requirements().forEach(item -> parts.add(item.text()));
            ctx.profile().constraints().forEach(item -> parts.add(item.text()));
            ctx.profile().outputs().forEach(item -> parts.add(item.text()));
            return String.join(" ", parts);
        }
    }

    /** Placeholder for a blueprint check that this milestone cannot perform; it reports why instead of passing. */
    public static final class UnsupportedCheck implements Check {
        private final CheckId id;
        private final boolean notApplicable;
        private final String reason;

        private UnsupportedCheck(CheckId id, boolean notApplicable, String reason) {
            this.id = id;
            this.notApplicable = notApplicable;
            this.reason = reason;
        }

        /** The check applies in principle but needs a model-based verifier that is not implemented. */
        public static UnsupportedCheck notRun(CheckId id, String reason) {
            return new UnsupportedCheck(id, false, reason);
        }

        /** The data the check needs does not exist in this milestone. */
        public static UnsupportedCheck notApplicable(CheckId id, String reason) {
            return new UnsupportedCheck(id, true, reason);
        }

        @Override
        public CheckId id() {
            return id;
        }

        @Override
        public CheckResult run(ValidationContext ctx) {
            return notApplicable ? CheckResult.notApplicable(id, reason) : CheckResult.notRun(id, reason);
        }
    }
}
