package org.promptoptimizer.validate;

/** One deterministic check (blueprint section 13.1: {@code Check { run(ValidationContext) -> CheckResult }}). */
public interface Check {
    CheckId id();

    CheckResult run(ValidationContext ctx);
}
