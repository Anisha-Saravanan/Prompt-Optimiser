package org.promptoptimizer.validate;

/** Placeholder for a blueprint check that this milestone cannot perform; it reports why instead of passing. */
public final class UnsupportedCheck implements Check {
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
