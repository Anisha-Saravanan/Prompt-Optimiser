package org.promptoptimizer.recover;

import java.util.List;
import java.util.Map;

/**
 * Blueprint 6.5: what the caller needs to know when the budget is below the smallest safe representation found.
 *
 * @param budget          the requested budget B
 * @param minimumTokens   M: tokens of the smallest representation that passed every check except the budget
 * @param deficit         M - B (always positive)
 * @param minimumSource   which plan (or the original) produced M
 * @param minimumText     the smallest safe representation; a caller may use it only knowingly (blueprint option 5)
 * @param composition     piece-wise token estimate of M by unit type, plus SCAFFOLD and OMISSION_MARKER; the parts are
 *                        counted separately, so their sum can differ slightly from {@code minimumTokens}
 * @param exact           whether the counter is the target tokenizer (false for the heuristic counter: M is an upper bound)
 * @param options         the blueprint's options, each marked as available or not in this build
 */
public record Infeasibility(int budget, int minimumTokens, int deficit, String minimumSource, String minimumText,
                            Map<String, Integer> composition, boolean exact, List<String> options) {
    public Infeasibility {
        composition = Map.copyOf(composition);
        options = List.copyOf(options);
    }
}
