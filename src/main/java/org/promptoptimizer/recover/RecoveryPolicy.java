package org.promptoptimizer.recover;

/**
 * Limits of the decision loop.
 *
 * @param maxRestoreRounds        restore-only revise rounds, 0..2. Blueprint round 3 re-runs scoring and budget
 *                                allocation (S8-S9), which do not exist yet, so it is not offered.
 * @param rejectLargerThanOriginal if true, a candidate with more tokens than the original input (as it would be
 *                                delivered) is rejected: optimizing must not make the input bigger. Equal is accepted.
 */
public record RecoveryPolicy(int maxRestoreRounds, boolean rejectLargerThanOriginal) {
    public static final int MAX_SUPPORTED_ROUNDS = 2;

    public RecoveryPolicy {
        if (maxRestoreRounds < 0 || maxRestoreRounds > MAX_SUPPORTED_ROUNDS) {
            throw new IllegalArgumentException("maxRestoreRounds must be 0.." + MAX_SUPPORTED_ROUNDS
                    + " (round 3 needs S8-S9, which are not implemented)");
        }
    }

    public static RecoveryPolicy defaults() {
        return new RecoveryPolicy(MAX_SUPPORTED_ROUNDS, true);
    }
}
