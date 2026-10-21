package org.promptoptimizer.validate;

import java.util.List;
import java.util.Objects;

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
