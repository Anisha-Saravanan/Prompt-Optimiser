package org.promptoptimizer.recover;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.promptoptimizer.lossless.LosslessPass;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.token.TokenCounter;

/** A named way of producing a candidate from the prepared documents. The controller knows nothing about operators. */
public record CandidatePlan(String name, Function<List<PreparedDocument>, LosslessResult> builder) {
    public CandidatePlan {
        Objects.requireNonNull(name);
        Objects.requireNonNull(builder);
    }

    /**
     * The ladder for the operators that exist today. Because every current operator is reversible, the ladder
     * is expressed in operators switched on:
     * <ul>
     *   <li>primary: whitespace normalization + exact-duplicate removal (the only operator that replaces content with a marker);</li>
     *   <li>less lossy: whitespace normalization only;</li>
     *   <li>lossless only: no operator at all (every unit KEPT; only file delimiters are added).</li>
     * </ul>
     * When lossy operators exist, the primary becomes the lossy plan and these rungs move down accordingly.
     */
    public static Ladder lossless(TokenCounter counter) {
        return new Ladder(
                new CandidatePlan("lossless-pass:whitespace+dedup", d -> new LosslessPass(counter,
                        new LosslessPass.Config(true, true, LosslessPass.Config.defaults().minDuplicateTokens())).run(d)),
                new CandidatePlan("lossless-pass:whitespace-only", d -> new LosslessPass(counter,
                        new LosslessPass.Config(true, false, LosslessPass.Config.defaults().minDuplicateTokens())).run(d)),
                new CandidatePlan("lossless-pass:no-operators", d -> new LosslessPass(counter,
                        new LosslessPass.Config(false, false, LosslessPass.Config.defaults().minDuplicateTokens())).run(d)));
    }

    /** The three plans of the ladder. A null fallback plan means that rung does not exist. */
    public record Ladder(CandidatePlan primary, CandidatePlan lessLossy, CandidatePlan losslessOnly) {
        public Ladder {
            Objects.requireNonNull(primary);
        }
    }
}
