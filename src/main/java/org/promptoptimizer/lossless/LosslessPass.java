package org.promptoptimizer.lossless;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.promptoptimizer.lossless.LosslessResult.Warning;
import org.promptoptimizer.model.Action;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.model.Level;
import org.promptoptimizer.model.ProtectedSpan;
import org.promptoptimizer.model.UnitType;
import org.promptoptimizer.token.TokenCounter;

/**
 * Blueprint stage S5. Applies reversible whitespace edits to prose units, then removes exact
 * duplicate units found by hash (keeping the first, and leaving a visible omission marker).
 *
 * <p>Guarantees, re-checkable with {@link LosslessResult#verify()}: every original document can be
 * rebuilt exactly from the result; no edit touches a HARD protected span or a fenced block; a unit
 * is merged away only if its HARD-span texts are contained in the surviving unit's; a whitespace
 * edit that would raise the token count is rejected; a duplicate is removed only if its marker
 * costs strictly fewer tokens than the unit.
 *
 * <p>Deliberately not done here: JSON re-serialization, boilerplate removal, log deduplication.
 */
public final class LosslessPass {
    public static final String OP_WHITESPACE = "whitespace-normalizer";
    public static final String OP_DEDUP = "exact-dedup";

    /** Unit types that are never merged away, even when an exact duplicate exists (placeholder policy). */
    public static final Set<UnitType> NEVER_MERGED = EnumSet.of(UnitType.TASK, UnitType.REQUIREMENT,
            UnitType.CONSTRAINT, UnitType.ERROR_TEXT, UnitType.EXAMPLE, UnitType.INPUT_FACT);

    /**
     * @param minDuplicateTokens a unit smaller than this is never removed as a duplicate (placeholder,
     *                           to be calibrated; short repeated lines are often meaningful)
     */
    public record Config(boolean normalizeWhitespace, boolean removeExactDuplicates, int minDuplicateTokens) {
        public Config {
            if (minDuplicateTokens < 1) {
                throw new IllegalArgumentException("minDuplicateTokens must be >= 1");
            }
        }

        public static Config defaults() {
            return new Config(true, true, 8);
        }
    }

    private final TokenCounter counter;
    private final Config config;
    private final WhitespaceNormalizer normalizer = new WhitespaceNormalizer();

    public LosslessPass(TokenCounter counter, Config config) {
        if (counter == null || config == null) {
            throw new IllegalArgumentException("counter and config are required");
        }
        this.counter = counter;
        this.config = config;
    }

    /** Working state of one unit while the pass runs. */
    private static final class Work {
        final PreparedDocument pd;
        final InformationUnit unit;
        final String l0;
        EditScript script;
        String l1;
        final int tokensBefore;
        int tokensL1;
        String mergedInto;
        String marker;
        int markerTokens;

        Work(PreparedDocument pd, InformationUnit unit, String l0, int tokensBefore) {
            this.pd = pd;
            this.unit = unit;
            this.l0 = l0;
            this.tokensBefore = tokensBefore;
            this.script = EditScript.empty(l0.length());
            this.l1 = l0;
            this.tokensL1 = tokensBefore;
        }
    }

    public LosslessResult run(List<PreparedDocument> docs) {
        Set<String> ids = new HashSet<>();
        for (PreparedDocument d : docs) {
            if (!ids.add(d.doc().id())) {
                throw new IllegalArgumentException("duplicate document id: " + d.doc().id());
            }
        }
        List<Warning> warnings = new ArrayList<>();
        Map<String, List<Work>> work = new LinkedHashMap<>();

        for (PreparedDocument pd : docs) {
            String text = pd.doc().text();
            List<int[]> excluded = new ArrayList<>(WhitespaceNormalizer.fencedRanges(text));
            for (ProtectedSpan s : pd.hardSpans()) {
                excluded.add(new int[] {s.start(), s.end()});
            }
            List<Work> list = new ArrayList<>();
            for (InformationUnit u : pd.units()) {
                String l0 = text.substring(u.start(), u.end());
                Work w = new Work(pd, u, l0, counter.count(l0).tokens());
                if (config.normalizeWhitespace() && pd.format().isProse()) {
                    WhitespaceNormalizer.Result r = normalizer.compute(text, pd.format(), u.start(), u.end(), excluded);
                    for (String s : r.skipped()) {
                        warnings.add(new Warning("EDIT_SKIPPED", u.id(), s));
                    }
                    String candidate = r.script().apply(l0);
                    int t = counter.count(candidate).tokens();
                    if (t > w.tokensBefore) {
                        warnings.add(new Warning("TOKENS_INCREASED", u.id(), "whitespace edit rejected: " + w.tokensBefore + " -> " + t));
                    } else {
                        w.script = r.script();
                        w.l1 = candidate;
                        w.tokensL1 = t;
                    }
                }
                list.add(w);
            }
            work.put(pd.doc().id(), list);
        }

        if (config.removeExactDuplicates()) {
            removeDuplicates(docs, work, warnings);
        }

        Map<String, List<UnitState>> states = new LinkedHashMap<>();
        for (PreparedDocument pd : docs) {
            List<UnitState> list = new ArrayList<>();
            for (Work w : work.get(pd.doc().id())) {
                list.add(toState(w));
            }
            states.put(pd.doc().id(), list);
        }
        LosslessResult result = new LosslessResult(docs, states, warnings, counter.id());
        result.verify();
        return result;
    }

    private void removeDuplicates(List<PreparedDocument> docs, Map<String, List<Work>> work, List<Warning> warnings) {
        List<PreparedDocument> order = new ArrayList<>();
        docs.stream().filter(d -> d.role() == DocRole.PROMPT).forEach(order::add);
        docs.stream().filter(d -> d.role() != DocRole.PROMPT).forEach(order::add);

        Map<String, Work> firstByHash = new HashMap<>();
        for (PreparedDocument pd : order) {
            if (!pd.format().isProse()) {
                continue;
            }
            for (Work w : work.get(pd.doc().id())) {
                String key = w.l1.stripTrailing();
                if (key.isEmpty()) {
                    continue;
                }
                String hash = Hashes.sha256(key);
                Work survivor = firstByHash.get(hash);
                if (survivor == null) {
                    firstByHash.put(hash, w);
                    continue;
                }
                String refusal = refusalReason(w, survivor);
                if (refusal != null) {
                    warnings.add(new Warning("DUPLICATE_NOT_MERGED", w.unit.id(), refusal + " (duplicate of " + survivor.unit.id() + ")"));
                    continue;
                }
                String marker = OmissionMarker.exactRepeat(survivor.pd.doc().id(), survivor.unit.ordinal());
                int markerTokens = counter.count(marker).tokens();
                if (markerTokens >= w.tokensL1) {
                    warnings.add(new Warning("DUPLICATE_NOT_MERGED", w.unit.id(),
                            "marker costs " + markerTokens + " tokens, unit only " + w.tokensL1));
                    continue;
                }
                w.mergedInto = survivor.unit.id();
                w.marker = marker;
                w.markerTokens = markerTokens;
            }
        }
    }

    /** Returns why {@code dup} must not be merged into {@code survivor}, or null if it may be. */
    private String refusalReason(Work dup, Work survivor) {
        if (NEVER_MERGED.contains(dup.unit.type())) {
            return "unit type " + dup.unit.type() + " is never merged";
        }
        if (dup.tokensL1 < config.minDuplicateTokens()) {
            return "below minDuplicateTokens (" + dup.tokensL1 + " < " + config.minDuplicateTokens() + ")";
        }
        List<String> dupSpans = containedHardSpanTexts(dup);
        List<String> survSpans = containedHardSpanTexts(survivor);
        if (dupSpans == null || survSpans == null) {
            return "a HARD span straddles the unit boundary";
        }
        Map<String, Long> have = survSpans.stream().collect(Collectors.groupingBy(s -> s, Collectors.counting()));
        for (Map.Entry<String, Long> e : dupSpans.stream().collect(Collectors.groupingBy(s -> s, Collectors.counting())).entrySet()) {
            if (have.getOrDefault(e.getKey(), 0L) < e.getValue()) {
                return "HARD-span texts of the duplicate are not contained in the surviving unit";
            }
        }
        return null;
    }

    /** Texts of HARD spans lying inside the unit, or null if some HARD span only partly overlaps it. */
    private static List<String> containedHardSpanTexts(Work w) {
        List<String> out = new ArrayList<>();
        String text = w.pd.doc().text();
        for (ProtectedSpan s : w.pd.hardSpans()) {
            if (s.end() <= w.unit.start() || w.unit.end() <= s.start()) {
                continue;
            }
            if (s.start() < w.unit.start() || s.end() > w.unit.end()) {
                return null;
            }
            out.add(text.substring(s.start(), s.end()));
        }
        return out;
    }

    private UnitState toState(Work w) {
        String unitId = w.unit.id();
        String hashBefore = Hashes.sha256(w.l0);
        if (w.mergedInto != null) {
            TransformationRecord rec = new TransformationRecord(unitId, Action.MERGED, OP_DEDUP, Level.LOSSLESS,
                    "EXACT_HASH_DUPLICATE", w.tokensBefore, w.markerTokens, hashBefore, Hashes.sha256(w.marker),
                    w.mergedInto, 0);
            return new UnitState(w.unit, Action.MERGED, "", EditScript.empty(w.l0.length()), w.l0, w.mergedInto, rec);
        }
        if (w.script.isEmpty()) {
            TransformationRecord rec = new TransformationRecord(unitId, Action.KEPT, "none", Level.LOSSLESS,
                    "UNCHANGED", w.tokensBefore, w.tokensL1, hashBefore, hashBefore, null, 0);
            return new UnitState(w.unit, Action.KEPT, w.l1, w.script, null, null, rec);
        }
        String reason = w.script.kinds().stream().map(Enum::name).sorted().collect(Collectors.joining("+"));
        TransformationRecord rec = new TransformationRecord(unitId, Action.TRANSFORMED, OP_WHITESPACE, Level.LOSSLESS,
                reason, w.tokensBefore, w.tokensL1, hashBefore, Hashes.sha256(w.l1), null, 0);
        return new UnitState(w.unit, Action.TRANSFORMED, w.l1, w.script, null, null, rec);
    }
}
