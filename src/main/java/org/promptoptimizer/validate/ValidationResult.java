package org.promptoptimizer.validate;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
}
