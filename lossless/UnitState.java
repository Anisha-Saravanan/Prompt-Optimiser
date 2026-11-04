package org.promptoptimizer.lossless;

import java.util.Objects;
import org.promptoptimizer.model.Action;
import org.promptoptimizer.model.InformationUnit;

/**
 * Representations of one unit after the lossless pass. L0 is the original text, L1 the lossless
 * text. L0 is never stored for kept units: it is recovered from L1 with the reverse map in
 * {@code script}. For MERGED units L1 is empty (the unit is replaced by an omission marker) and the
 * removed original is stored in {@code removedOriginal}.
 */
public record UnitState(InformationUnit unit, Action action, String l1, EditScript script,
                        String removedOriginal, String mergedInto, TransformationRecord record) {
    public UnitState {
        Objects.requireNonNull(unit);
        Objects.requireNonNull(action);
        Objects.requireNonNull(l1);
        Objects.requireNonNull(script);
        Objects.requireNonNull(record);
        if (action == Action.MERGED) {
            if (removedOriginal == null || mergedInto == null || !l1.isEmpty()) {
                throw new IllegalArgumentException("MERGED state needs removedOriginal, mergedInto and empty l1");
            }
        } else if (removedOriginal != null || mergedInto != null) {
            throw new IllegalArgumentException("only MERGED states carry removedOriginal/mergedInto");
        }
    }

    public boolean merged() {
        return action == Action.MERGED;
    }

    /** The exact original text of the unit, reconstructed from the stored reverse information. */
    public String original() {
        return merged() ? removedOriginal : script.revert(l1);
    }
}
