package org.promptoptimizer.segment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.model.UnitType;
import org.promptoptimizer.task.ExtractedItem;
import org.promptoptimizer.task.ItemKind;
import org.promptoptimizer.task.Provenance.Basis;
import org.promptoptimizer.task.RuleBasedTaskProfiler;
import org.promptoptimizer.task.SourceRef;
import org.promptoptimizer.task.TaskProfile;
import org.promptoptimizer.token.TokenCounter;

/**
 * Types the units produced by a base {@link Segmenter} using a {@link TaskProfile}. Unit boundaries,
 * ordinals, offsets and token counts are exactly those of the base segmenter; only {@code type}
 * changes, so the partition property is preserved (and re-verified here).
 *
 * <p>A unit gets a type only when the evidence is unambiguous, otherwise it stays {@code UNKNOWN}:
 * <ul>
 *   <li>TASK / REQUIREMENT / CONSTRAINT: every item overlapping the unit is EXPLICIT_LABEL or CUE_RULE,
 *       lies entirely inside the unit, maps to one type, and everything else in the unit is scaffolding
 *       (whitespace, list markers, recognised section labels such as "TASK:"). A TASK item whose
 *       span contains all other items of the unit (e.g. "Write a haiku using at most 17 syllables.")
 *       types the unit TASK. OUTPUT_EXPECTATION items map to REQUIREMENT because {@code UnitType} has
 *       no output type.</li>
 *   <li>CODE: the unit is exactly one terminated fenced block whose opening fence has a language tag.
 *       A bare fence stays UNKNOWN (no evidence of what it contains).</li>
 *   <li>Left UNKNOWN: units with mixed kinds, uncovered prose, STRUCTURAL_INFERENCE items, unpromoted
 *       candidates (possible repeats, never verified), block quotes, quoted text, greetings.
 *       Nothing is typed from cue words alone; REPEAT, NOISE, CONTEXT etc. are never assigned here.</li>
 * </ul>
 * A null profile makes this segmenter a pass-through of the base segmenter's output.
 */
public final class ProfileAwareSegmenter implements Segmenter {
    private static final Pattern LIST_MARKER = Pattern.compile("^(?:[-*+\\u2022]|\\d{1,3}[.)])$");
    private static final Pattern LABEL_LINE = Pattern.compile("^(?:#{1,6}\\s*)?(?:\\*\\*|__)?\\s*([A-Za-z][A-Za-z ]{1,24}?)\\s*(?:\\*\\*|__)?\\s*:?\\s*(?:\\*\\*|__)?$");
    private static final Pattern LANG_TAG = Pattern.compile("^[A-Za-z0-9_+#.\\-]+$");

    private final Segmenter base;
    private final TaskProfile profile;

    public ProfileAwareSegmenter(Segmenter base, TaskProfile profile) {
        if (base == null) {
            throw new IllegalArgumentException("base segmenter is required");
        }
        this.base = base;
        this.profile = profile;
    }

    @Override
    public List<InformationUnit> segment(CanonicalDocument doc, TokenCounter counter) {
        List<InformationUnit> units = base.segment(doc, counter);
        if (profile == null) {
            return units;
        }
        if (!profile.prompt().id().equals(doc.id()) || !profile.prompt().text().equals(doc.text())) {
            throw new IllegalArgumentException("task profile was built from a different document than '" + doc.id() + "'");
        }
        List<InformationUnit> typed = new ArrayList<>(units.size());
        for (InformationUnit u : units) {
            typed.add(new InformationUnit(u.docId(), u.ordinal(), u.start(), u.end(), classify(doc, u), u.tokens()));
        }
        Segmenter.verifyPartition(doc, typed);
        return typed;
    }

    private UnitType classify(CanonicalDocument doc, InformationUnit u) {
        String unitText = u.slice(doc);
        List<ExtractedItem> overlapping = new ArrayList<>();
        for (ExtractedItem it : allItems()) {
            if (overlaps(it.source(), u)) {
                overlapping.add(it);
            }
        }
        if (overlapping.isEmpty()) {
            return isTaggedFence(unitText) ? UnitType.CODE : UnitType.UNKNOWN;
        }
        // items present: never CODE; type only on unambiguous profile evidence
        boolean[] covered = new boolean[unitText.length()];
        UnitType type = null;
        ExtractedItem taskItem = null;
        List<ExtractedItem> others = new ArrayList<>();
        for (ExtractedItem it : overlapping) {
            if (it.provenance().basis() == Basis.STRUCTURAL_INFERENCE || !inside(it.source(), u)) {
                return UnitType.UNKNOWN;
            }
            if (it.id().startsWith("X")) {
                return UnitType.UNKNOWN; // unpromoted candidate: ambiguous by definition
            }
            for (SourceRef d : it.duplicateSources()) {
                if (overlaps(d, u)) {
                    if (!inside(d, u)) {
                        return UnitType.UNKNOWN;
                    }
                    mark(covered, d, u);
                }
            }
            mark(covered, it.source(), u);
            UnitType t = map(it.kind());
            if (it.kind() == ItemKind.TASK) {
                taskItem = it;
            } else {
                others.add(it);
            }
            if (type == null) {
                type = t;
            } else if (type != t) {
                type = UnitType.UNKNOWN;
            }
        }
        if (type == UnitType.UNKNOWN && taskItem != null) {
            boolean dominates = true;
            for (ExtractedItem o : others) {
                if (o.source().start() < taskItem.source().start() || o.source().end() > taskItem.source().end()) {
                    dominates = false;
                }
            }
            type = dominates ? UnitType.TASK : UnitType.UNKNOWN;
        }
        if (type == null || type == UnitType.UNKNOWN || !onlyScaffoldingUncovered(unitText, covered)) {
            return UnitType.UNKNOWN;
        }
        return type;
    }

    private List<ExtractedItem> allItems() {
        List<ExtractedItem> all = new ArrayList<>();
        profile.taskStatement().ifPresent(all::add);
        all.addAll(profile.requirements());
        all.addAll(profile.constraints());
        all.addAll(profile.outputs());
        all.addAll(profile.candidates());
        return all;
    }

    private static UnitType map(ItemKind k) {
        return switch (k) {
            case TASK -> UnitType.TASK;
            case CONSTRAINT -> UnitType.CONSTRAINT;
            case REQUIREMENT, OUTPUT_EXPECTATION -> UnitType.REQUIREMENT;
        };
    }

    private static boolean overlaps(SourceRef r, InformationUnit u) {
        return r.start() < u.end() && u.start() < r.end();
    }

    private static boolean inside(SourceRef r, InformationUnit u) {
        return r.start() >= u.start() && r.end() <= u.end();
    }

    private static void mark(boolean[] covered, SourceRef r, InformationUnit u) {
        for (int i = r.start(); i < r.end(); i++) {
            covered[i - u.start()] = true;
        }
    }

    /** Uncovered text, line by line, must be blank, a list marker, or a recognised section label. */
    private static boolean onlyScaffoldingUncovered(String unitText, boolean[] covered) {
        int lineStart = 0;
        while (lineStart <= unitText.length()) {
            int nl = unitText.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? unitText.length() : nl;
            StringBuilder residue = new StringBuilder();
            for (int i = lineStart; i < lineEnd; i++) {
                residue.append(covered[i] ? ' ' : unitText.charAt(i));
            }
            String r = residue.toString().replaceAll("\\s+", " ").strip();
            if (!r.isEmpty() && !LIST_MARKER.matcher(r).matches() && !isLabel(r)) {
                return false;
            }
            if (nl < 0) {
                break;
            }
            lineStart = nl + 1;
        }
        return true;
    }

    private static boolean isLabel(String r) {
        var m = LABEL_LINE.matcher(r);
        return m.matches() && RuleBasedTaskProfiler.isSectionLabel(m.group(1));
    }

    /** Exactly one terminated fenced block with a language tag, optionally followed by blank lines. */
    private static boolean isTaggedFence(String unitText) {
        String[] lines = unitText.split("\n", -1);
        int first = 0;
        while (first < lines.length && lines[first].isBlank()) {
            first++;
        }
        int last = lines.length - 1;
        while (last >= 0 && lines[last].isBlank()) {
            last--;
        }
        if (first >= last) {
            return false;
        }
        String open = lines[first].strip();
        String marker = open.startsWith("```") ? "```" : open.startsWith("~~~") ? "~~~" : null;
        if (marker == null) {
            return false;
        }
        String info = open.substring(marker.length()).strip();
        if (!LANG_TAG.matcher(info).matches()) {
            return false;
        }
        for (int i = first + 1; i <= last; i++) {
            if (lines[i].strip().startsWith(marker)) {
                return i == last && lines[i].strip().equals(marker);
            }
        }
        return false;
    }
}
