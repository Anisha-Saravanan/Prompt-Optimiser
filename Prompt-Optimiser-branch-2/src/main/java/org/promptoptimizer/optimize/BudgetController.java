package org.promptoptimizer.optimize;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.promptoptimizer.assemble.Assembler;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.LosslessResult.ContentFormat;
import org.promptoptimizer.lossless.LosslessResult.PreparedDocument;
import org.promptoptimizer.lossless.LosslessResult.UnitState;
import org.promptoptimizer.model.Model.Action;
import org.promptoptimizer.model.Model.InformationUnit;
import org.promptoptimizer.model.Model.Level;
import org.promptoptimizer.model.Model.ProtectedSpan;
import org.promptoptimizer.model.Model.UnitType;
import org.promptoptimizer.task.TaskProfile;
import org.promptoptimizer.task.TaskProfile.ExtractedItem;
import org.promptoptimizer.token.TokenCounter;

/**
 * Budget-aware, unit-level selection. Hard spans and conservative essential types are never dropped;
 * every proposed set is assembled and recounted, including file scaffolding and duplicate markers.
 */
public final class BudgetController {
    public static final String OP_SELECT = "typed-budget-selection";
    public static final String REASON_SELECTED_OUT = "BUDGET_SELECTION";

    private final Bm25JaccardScorer scorer;

    public BudgetController() {
        this(new Bm25JaccardScorer());
    }

    public BudgetController(Bm25JaccardScorer scorer) {
        this.scorer = Objects.requireNonNull(scorer);
    }

    public SelectionResult select(LosslessResult base, TaskProfile profile, Integer budget, TokenCounter counter) {
        return select(base, profile, budget, counter, DependencyGraph.infer(base.documents()), Set.of());
    }

    public SelectionResult select(LosslessResult base, TaskProfile profile, Integer budget, TokenCounter counter,
                                  DependencyGraph dependencies) {
        return select(base, profile, budget, counter, dependencies, Set.of());
    }

    /** Re-selects after restoration while forcing the repaired units to remain in the candidate. */
    public SelectionResult select(LosslessResult base, TaskProfile profile, Integer budget, TokenCounter counter,
                                  DependencyGraph dependencies, Set<String> forcedKeepUnitIds) {
        Objects.requireNonNull(base);
        Objects.requireNonNull(counter);
        Objects.requireNonNull(dependencies);
        Objects.requireNonNull(forcedKeepUnitIds);
        if (budget != null && budget < 0) {
            throw new IllegalArgumentException("budget must be >= 0");
        }
        if (!counter.id().equals(base.tokenizerId())) {
            throw new IllegalArgumentException("budget controller counter differs from the lossless result tokenizer");
        }
        Assembler assembler = new Assembler(counter);
        if (budget == null) {
            return unchanged(base, assembler, "no budget supplied");
        }
        if (profile == null) {
            return unchanged(base, assembler, "no task profile; selection is disabled");
        }

        PreparedDocument prompt = base.documents().stream()
                .filter(d -> d.role() == LosslessResult.DocRole.PROMPT).findFirst().orElse(null);
        if (prompt == null || !profile.prompt().id().equals(prompt.doc().id())
                || !profile.prompt().text().equals(prompt.doc().text())) {
            throw new IllegalArgumentException("task profile does not match the lossless result prompt");
        }
        String query = taskQuery(profile, prompt);
        if (query.isBlank()) {
            return unchanged(base, assembler, "task and requirement query is empty; selection is disabled");
        }

        List<UnitRef> units = units(base);
        List<Bm25JaccardScorer.UnitText> text = units.stream()
                .filter(u -> !u.state().merged())
                .map(u -> new Bm25JaccardScorer.UnitText(u.state().unit().id(),
                        maskedUnitText(u.document(), u.state().unit(), u.state().original())))
                .toList();
        Map<String, Bm25JaccardScorer.UnitScore> scores = new HashMap<>();
        for (Bm25JaccardScorer.UnitScore score : scorer.score(text, query)) {
            scores.put(score.unitId(), score);
        }

        Map<String, UnitRef> byId = new HashMap<>();
        units.forEach(unit -> byId.put(unit.state().unit().id(), unit));
        Set<String> eligible = new LinkedHashSet<>();
        Set<String> required = new LinkedHashSet<>();
        Set<String> alreadyDropped = new LinkedHashSet<>();
        for (UnitRef unit : units) {
            UnitState state = unit.state();
            if (state.merged()) {
                continue;
            }
            if (state.action() == Action.DROPPED) {
                if (forcedKeepUnitIds.contains(state.unit().id())) {
                    throw new IllegalArgumentException("a forced unit must be restored before budget reselection: "
                            + state.unit().id());
                }
                alreadyDropped.add(state.unit().id());
                continue;
            }
            UnitType type = effectiveType(unit);
            if (hasHardSpan(unit.document(), state.unit()) || isEssential(type)) {
                required.add(state.unit().id());
            } else if (type == UnitType.CODE || type == UnitType.DOC || type == UnitType.EXAMPLE
                    || type == UnitType.CONTEXT || type == UnitType.NOISE || type == UnitType.REPEAT) {
                eligible.add(state.unit().id());
            } else {
                required.add(state.unit().id());
            }
        }
        for (String id : forcedKeepUnitIds) {
            if (!byId.containsKey(id)) {
                throw new IllegalArgumentException("forced unit is unknown: " + id);
            }
            if (alreadyDropped.contains(id)) {
                throw new IllegalArgumentException("forced unit must be restored before budget reselection: " + id);
            }
            required.add(id);
        }

        Set<String> selected = new LinkedHashSet<>(closure(required, dependencies, byId));
        Set<String> unsupported = new HashSet<>(selected);
        unsupported.removeAll(byId.keySet());
        if (!unsupported.isEmpty()) {
            throw new IllegalArgumentException("dependency graph refers to unknown units: " + unsupported);
        }
        int currentTokens = countAfterDropping(base, eligible, selected, assembler);

        for (Priority priority : Priority.values()) {
            while (true) {
                Candidate best = null;
                for (String id : eligible) {
                    if (selected.contains(id) || priority(byId.get(id)) != priority) {
                        continue;
                    }
                    Set<String> closure = closure(Set.of(id), dependencies, byId);
                    Set<String> proposed = new LinkedHashSet<>(selected);
                    proposed.addAll(closure);
                    Set<String> newUnits = new LinkedHashSet<>(proposed);
                    newUnits.removeAll(selected);
                    if (newUnits.isEmpty()) {
                        continue;
                    }
                    int tokens = countAfterDropping(base, eligible, proposed, assembler);
                    if (tokens > budget) {
                        continue;
                    }
                    int addedTokens = Math.max(0, tokens - currentTokens);
                    double benefit = newUnits.stream().mapToDouble(unitId -> value(unitId, byId, scores)).sum();
                    double density = benefit / Math.max(addedTokens, 1);
                    Candidate candidate = new Candidate(id, proposed, tokens, density);
                    if (best == null || Candidate.ORDER.compare(candidate, best) < 0) {
                        best = candidate;
                    }
                }
                if (best == null) {
                    break;
                }
                selected = best.selected();
                currentTokens = best.tokens();
            }
        }

        Set<String> dropped = new LinkedHashSet<>(alreadyDropped);
        Set<String> newlyDropped = new LinkedHashSet<>(eligible);
        newlyDropped.removeAll(selected);
        dropped.addAll(newlyDropped);
        LosslessResult result = newlyDropped.isEmpty() ? base : base.withDropped(newlyDropped, REASON_SELECTED_OUT);
        int exactTokens = assembler.assemble(result).tokens();
        if (exactTokens != currentTokens) {
            throw new IllegalStateException("budget selection recount changed from " + currentTokens + " to " + exactTokens);
        }
        Map<String, Double> combinedScores = new HashMap<>();
        scores.forEach((id, score) -> combinedScores.put(id, score.combined()));
        return new SelectionResult(result, List.copyOf(selected), List.copyOf(dropped), exactTokens, query,
                combinedScores, true, dropped.isEmpty() ? "all eligible units fit" : "units omitted by typed budget selection");
    }

    private static SelectionResult unchanged(LosslessResult base, Assembler assembler, String reason) {
        return new SelectionResult(base, List.of(), List.of(), assembler.assemble(base).tokens(), "", Map.of(), false, reason);
    }

    private static Set<String> closure(Set<String> seeds, DependencyGraph dependencies, Map<String, UnitRef> units) {
        Set<String> result = dependencies.closure(seeds);
        for (String id : result) {
            if (!units.containsKey(id)) {
                throw new IllegalArgumentException("dependency graph refers to unknown unit: " + id);
            }
        }
        return result;
    }

    private static int countAfterDropping(LosslessResult base, Set<String> eligible, Set<String> selected,
                                          Assembler assembler) {
        Set<String> dropped = new LinkedHashSet<>(eligible);
        dropped.removeAll(selected);
        LosslessResult candidate = dropped.isEmpty() ? base : base.withDropped(dropped, REASON_SELECTED_OUT);
        return assembler.assemble(candidate).tokens();
    }

    private static List<UnitRef> units(LosslessResult result) {
        List<UnitRef> units = new ArrayList<>();
        for (PreparedDocument document : result.documents()) {
            for (UnitState state : result.states(document.doc().id())) {
                units.add(new UnitRef(document, state));
            }
        }
        return List.copyOf(units);
    }

    private static boolean isEssential(UnitType type) {
        return switch (type) {
            case TASK, REQUIREMENT, CONSTRAINT, INPUT_FACT, ERROR_TEXT, SCHEMA, UNKNOWN -> true;
            default -> false;
        };
    }

    private static UnitType effectiveType(UnitRef ref) {
        UnitType declared = ref.state().unit().type();
        if (declared != UnitType.UNKNOWN) {
            return declared;
        }
        ContentFormat format = ref.document().format();
        if (format == ContentFormat.SOURCE_CODE) {
            return UnitType.CODE;
        }
        if (format == ContentFormat.STRUCTURED_DATA) {
            return UnitType.SCHEMA;
        }
        if (format == ContentFormat.LOG) {
            return UnitType.CONTEXT;
        }
        if (format == ContentFormat.MARKDOWN && ref.document().role() == LosslessResult.DocRole.FILE) {
            return UnitType.DOC;
        }
        return UnitType.UNKNOWN;
    }

    private static boolean hasHardSpan(PreparedDocument document, InformationUnit unit) {
        for (ProtectedSpan span : document.spans()) {
            if (span.level() == Level.HARD && span.start() < unit.end() && unit.start() < span.end()) {
                return true;
            }
        }
        return false;
    }

    private static Priority priority(UnitRef ref) {
        return switch (effectiveType(ref)) {
            case CONTEXT, NOISE, REPEAT -> Priority.OPTIONAL;
            default -> Priority.IMPORTANT;
        };
    }

    private static double value(String id, Map<String, UnitRef> units,
                                Map<String, Bm25JaccardScorer.UnitScore> scores) {
        UnitType type = effectiveType(units.get(id));
        double prior = switch (type) {
            case CODE -> 0.75;
            case DOC -> 0.65;
            case EXAMPLE -> 0.55;
            case CONTEXT -> 0.35;
            case NOISE -> 0.15;
            case REPEAT -> 0.1;
            default -> 0.5;
        };
        return prior + scores.getOrDefault(id, new Bm25JaccardScorer.UnitScore(id, 0, 0, 0, 0)).combined();
    }

    private static String taskQuery(TaskProfile profile, PreparedDocument prompt) {
        List<String> parts = new ArrayList<>();
        profile.taskStatement().ifPresent(item -> parts.add(maskItem(item, prompt)));
        for (ExtractedItem item : profile.requirements()) {
            parts.add(maskItem(item, prompt));
        }
        for (ExtractedItem item : profile.outputs()) {
            parts.add(maskItem(item, prompt));
        }
        return String.join(" ", parts).strip();
    }

    private static String maskItem(ExtractedItem item, PreparedDocument prompt) {
        int start = item.source().start();
        int end = item.source().end();
        return maskedRange(prompt.doc().text().substring(start, end), start, prompt.spans());
    }

    private static String maskedUnitText(PreparedDocument document, InformationUnit unit, String original) {
        return maskedRange(original, unit.start(), document.spans());
    }

    private static String maskedRange(String text, int absoluteStart, List<ProtectedSpan> spans) {
        List<int[]> ranges = new ArrayList<>();
        int absoluteEnd = absoluteStart + text.length();
        for (ProtectedSpan span : spans) {
            if (span.level() != Level.HARD || span.start() >= absoluteEnd || span.end() <= absoluteStart) {
                continue;
            }
            ranges.add(new int[] {Math.max(span.start(), absoluteStart) - absoluteStart,
                    Math.min(span.end(), absoluteEnd) - absoluteStart});
        }
        ranges.sort(Comparator.comparingInt(range -> range[0]));
        StringBuilder masked = new StringBuilder(text.length());
        int cursor = 0;
        for (int[] range : ranges) {
            if (range[0] < cursor) {
                continue;
            }
            masked.append(text, cursor, range[0]).append(" protected ");
            cursor = range[1];
        }
        masked.append(text, cursor, text.length());
        return masked.toString();
    }

    private enum Priority {
        OPTIONAL,
        IMPORTANT
    }

    private record UnitRef(PreparedDocument document, UnitState state) {}

    private record Candidate(String id, Set<String> selected, int tokens, double density) {
        private static final Comparator<Candidate> ORDER = Comparator.comparingDouble(Candidate::density).reversed()
                .thenComparing(Candidate::id);
    }

    /** Selection audit; importance values are heuristic and uncalibrated. */
    public record SelectionResult(LosslessResult result, List<String> selectedUnitIds, List<String> droppedUnitIds,
                                  int tokens, String query, Map<String, Double> combinedScores,
                                  boolean applied, String reason) {
        public SelectionResult {
            Objects.requireNonNull(result);
            selectedUnitIds = List.copyOf(selectedUnitIds);
            droppedUnitIds = List.copyOf(droppedUnitIds);
            Objects.requireNonNull(query);
            combinedScores = Map.copyOf(combinedScores);
            Objects.requireNonNull(reason);
        }
    }
}
