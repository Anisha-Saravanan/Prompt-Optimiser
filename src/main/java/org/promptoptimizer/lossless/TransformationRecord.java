package org.promptoptimizer.lossless;

import java.util.Objects;
import org.promptoptimizer.model.Action;
import org.promptoptimizer.model.Level;

/**
 * Audit record: exactly one per unit, holding its terminal action (blueprint section 7.4).
 * Differences from the blueprint's sketch: ids are the existing string unit ids (no UUIDs, no run
 * concept yet), and there is no score snapshot because no scoring exists yet.
 *
 * @param relatedUnitId for MERGED units, the surviving unit that carries the content; otherwise null
 */
public record TransformationRecord(String unitId, Action action, String operator, Level level, String reasonCode,
                                   int tokensBefore, int tokensAfter, String hashBefore, String hashAfter,
                                   String relatedUnitId, int round) {
    public TransformationRecord {
        Objects.requireNonNull(unitId);
        Objects.requireNonNull(action);
        Objects.requireNonNull(operator);
        Objects.requireNonNull(level);
        Objects.requireNonNull(reasonCode);
        Objects.requireNonNull(hashBefore);
        Objects.requireNonNull(hashAfter);
    }
}
