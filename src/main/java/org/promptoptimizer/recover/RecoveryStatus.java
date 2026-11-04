package org.promptoptimizer.recover;

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
