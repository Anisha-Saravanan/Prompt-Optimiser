package org.promptoptimizer.validate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.promptoptimizer.assemble.AssembledInput;
import org.promptoptimizer.lossless.LosslessResult.DocRole;
import org.promptoptimizer.lossless.LosslessResult.PreparedDocument;
import org.promptoptimizer.protect.ProtectedSpanDetector;
import org.promptoptimizer.task.TaskProfile;
import org.promptoptimizer.token.TokenCounter;

/** Per-check results of one validation run, in check-number order. */
public record ValidationResult(List<CheckResult> checks) {
    public ValidationResult {
        checks = List.copyOf(checks);
    }

    /** True if no check failed. Checks that were not applicable or not run do not fail, and are listed in {@link #notVerified()}. */
    public boolean mandatoryPass() {
        return checks.stream().noneMatch(c -> c.status() == CheckResult.Status.FAIL);
    }

    public List<Finding> findings() {
        List<Finding> all = new ArrayList<>();
        checks.forEach(c -> all.addAll(c.findings()));
        return all;
    }

    public List<Finding> errors() {
        return findings().stream().filter(Finding::isError).collect(Collectors.toList());
    }

    public Set<CheckId> failingChecks() {
        Set<CheckId> ids = new LinkedHashSet<>();
        checks.stream().filter(c -> c.status() == CheckResult.Status.FAIL).forEach(c -> ids.add(c.check()));
        return ids;
    }

    public Set<String> errorFingerprints() {
        return errors().stream().map(Finding::fingerprint).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public CheckResult check(CheckId id) {
        return checks.stream().filter(c -> c.check() == id).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no result for " + id));
    }

    /** Checks whose property was NOT verified (not applicable or not run), with the reason. */
    public List<CheckResult> notVerified() {
        return checks.stream().filter(c -> c.status() == CheckResult.Status.NOT_APPLICABLE
                || c.status() == CheckResult.Status.NOT_RUN).collect(Collectors.toList());
    }

    public String summary() {
        StringBuilder sb = new StringBuilder(mandatoryPass() ? "PASS" : "FAIL");
        for (CheckResult c : checks) {
            sb.append("; ").append(c.check().number()).append('=').append(c.status());
        }
        return sb.toString();
    }

    /** One deterministic check (blueprint section 13.1: {@code Check { run(ValidationContext) -> CheckResult }}). */
    public interface Check {
        CheckId id();

        CheckResult run(ValidationContext ctx);
    }

    /** The eleven checks of blueprint section 7.1, numbered as in the blueprint. */
    public enum CheckId {
        BUDGET(1, "Token-budget compliance"),
        PROTECTED_SPANS(2, "Protected-span preservation"),
        EXACT_VALUES(3, "Exact-value preservation"),
        REQUIREMENTS(4, "Requirement and constraint recall"),
        IMPORTANT_FACTS(5, "Important-fact preservation"),
        STRUCTURE(6, "Structural validity"),
        CLOSURE(7, "Reference and dependency closure"),
        SEMANTIC(8, "Semantic consistency"),
        TASK_RISK(9, "Task-specific risk checks"),
        PROVENANCE(10, "Provenance"),
        PROBE(11, "Reconstruction probe");

        private final int number;
        private final String title;

        CheckId(int number, String title) {
            this.number = number;
            this.title = title;
        }

        public int number() {
            return number;
        }

        public String title() {
            return title;
        }
    }

    /**
     * Outcome of one check. NOT_APPLICABLE: the check does not apply to this input or the data it needs does
     * not exist yet. NOT_RUN: the check needs a model-based verifier that is not implemented. Neither counts
     * as a pass of the underlying property; {@code note} says why.
     */
    public record CheckResult(CheckId check, Status status, String note, List<Finding> findings) {
        public enum Status { PASS, FAIL, NOT_APPLICABLE, NOT_RUN }

        public CheckResult {
            Objects.requireNonNull(check);
            Objects.requireNonNull(status);
            Objects.requireNonNull(note);
            findings = List.copyOf(findings);
        }

        /** PASS unless some finding is an ERROR (warnings alone do not fail a check). */
        public static CheckResult of(CheckId id, List<Finding> findings) {
            boolean error = findings.stream().anyMatch(Finding::isError);
            return new CheckResult(id, error ? Status.FAIL : Status.PASS, "", findings);
        }

        public static CheckResult notApplicable(CheckId id, String reason) {
            return new CheckResult(id, Status.NOT_APPLICABLE, reason, List.of());
        }

        public static CheckResult notRun(CheckId id, String reason) {
            return new CheckResult(id, Status.NOT_RUN, reason, List.of());
        }
    }

    /** Failure classes of the preservation validator (blueprint section 7.1). Not all can arise yet; see {@link CheckId}. */
    public enum FailureClass {
        OVERBUDGET, SPAN_LOST, VALUE_LOST, REQ_MISSING, FACT_LOST, STRUCT_INVALID, DANGLING_REF,
        SEMANTIC_DRIFT, TASK_RISK, PROVENANCE, PROBE_FLAG
    }

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

    /**
     * Everything a validation run reads. The originals are the prepared documents as they were BEFORE any
     * operator ran; the validator never reads the lossless pass's own state, so it checks the candidate
     * independently of how the candidate was built.
     *
     * @param profile  task profile of the prompt document, or null (requirement check is then NOT_APPLICABLE)
     * @param budget   token budget, or null for none (budget check is then NOT_APPLICABLE)
     * @param detector re-run on the original documents; its spans are added to the spans the caller supplied
     */
    public record ValidationContext(List<PreparedDocument> originals, TaskProfile profile, Integer budget,
                                    TokenCounter counter, ProtectedSpanDetector detector, AssembledInput candidate) {
        public ValidationContext {
            Objects.requireNonNull(originals);
            Objects.requireNonNull(counter);
            Objects.requireNonNull(detector);
            Objects.requireNonNull(candidate);
            originals = List.copyOf(originals);
            if (budget != null && budget < 0) {
                throw new IllegalArgumentException("budget must be >= 0");
            }
            Set<String> ids = new HashSet<>();
            int prompts = 0;
            for (PreparedDocument d : originals) {
                if (!ids.add(d.doc().id())) {
                    throw new IllegalArgumentException("duplicate document id: " + d.doc().id());
                }
                if (d.role() == DocRole.PROMPT) {
                    prompts++;
                }
            }
            if (prompts > 1) {
                throw new IllegalArgumentException("at most one PROMPT document is supported, got " + prompts);
            }
            if (profile != null) {
                boolean match = originals.stream().anyMatch(d -> d.role() == DocRole.PROMPT
                        && d.doc().id().equals(profile.prompt().id()) && d.doc().text().equals(profile.prompt().text()));
                if (!match) {
                    throw new IllegalArgumentException("the task profile was not built from the PROMPT document");
                }
            }
        }

        public ValidationContext withCandidate(AssembledInput other) {
            return new ValidationContext(originals, profile, budget, counter, detector, other);
        }

        /** Documents in the order the baseline assembly delivers them: the prompt first, then files in the given order. */
        public List<PreparedDocument> deliveryOrder() {
            List<PreparedDocument> out = new ArrayList<>();
            originals.stream().filter(d -> d.role() == DocRole.PROMPT).forEach(out::add);
            originals.stream().filter(d -> d.role() != DocRole.PROMPT).forEach(out::add);
            return out;
        }
    }
}
