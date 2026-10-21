package org.promptoptimizer.lossless;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.promptoptimizer.model.Action;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.model.Level;
import org.promptoptimizer.model.ProtectedSpan;

/** Output of {@link LosslessPass}: per-unit states, the audit records and any warnings. */
public final class LosslessResult {
    /** A non-fatal observation, such as an edit that was deliberately skipped. */
    public record Warning(String code, String unitId, String message) {}

    private final List<PreparedDocument> documents;
    private final Map<String, List<UnitState>> states;
    private final List<Warning> warnings;
    private final String tokenizerId;

    LosslessResult(List<PreparedDocument> documents, Map<String, List<UnitState>> states, List<Warning> warnings,
                   String tokenizerId) {
        this.documents = List.copyOf(documents);
        Map<String, List<UnitState>> copy = new LinkedHashMap<>();
        states.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.states = copy;
        this.warnings = List.copyOf(warnings);
        this.tokenizerId = tokenizerId;
    }

    public List<PreparedDocument> documents() {
        return documents;
    }

    public List<UnitState> states(String docId) {
        List<UnitState> s = states.get(docId);
        if (s == null) {
            throw new IllegalArgumentException("unknown document: " + docId);
        }
        return s;
    }

    public List<Warning> warnings() {
        return warnings;
    }

    /** Id of the token counter used; the assembler must use the same one. */
    public String tokenizerId() {
        return tokenizerId;
    }

    /** One record per unit, in document order. */
    public List<TransformationRecord> records() {
        List<TransformationRecord> out = new ArrayList<>();
        for (PreparedDocument d : documents) {
            for (UnitState s : states(d.doc().id())) {
                out.add(s.record());
            }
        }
        return out;
    }

    public int tokensBefore() {
        return records().stream().mapToInt(TransformationRecord::tokensBefore).sum();
    }

    /** Tokens of the lossless text plus omission markers, excluding assembly scaffolding. */
    public int tokensAfter() {
        return records().stream().mapToInt(TransformationRecord::tokensAfter).sum();
    }

    /** Rebuilds a document's original text from the stored reverse information only. */
    public String reconstruct(String docId) {
        StringBuilder sb = new StringBuilder();
        for (UnitState s : states(docId)) {
            sb.append(s.original());
        }
        return sb.toString();
    }

    /** The state of one unit, looked up across all documents. */
    public UnitState state(String unitId) {
        for (List<UnitState> l : states.values()) {
            for (UnitState s : l) {
                if (s.unit().id().equals(unitId)) {
                    return s;
                }
            }
        }
        throw new IllegalArgumentException("unknown unit " + unitId);
    }

    /** True if the unit's text is its original text (KEPT or already RESTORED), so restoring it would change nothing. */
    public static boolean isOriginal(UnitState s) {
        return s.action() == Action.KEPT || s.action() == Action.RESTORED;
    }

    /**
     * Restore-only recovery primitive (blueprint 7.2): returns a NEW result in which every listed unit that is not
     * already original is put back verbatim, using only the reverse information stored in this result (the reverse
     * map of an edited unit, or the removed original of a merged unit). Each restored unit gets a RESTORED record
     * (operator {@link #OP_RESTORE}, level LOSSLESS, the given reason and round). Units that are already original,
     * and units not listed, are carried over unchanged. This result is not modified. The new result is verified.
     * It never introduces a new transformation.
     *
     * @param round >= 1, the recovery round that performs the restore
     * @throws IllegalArgumentException for an unknown unit id or a round below 1
     */
    public LosslessResult withRestored(Collection<String> unitIds, int round, String reasonCode) {
        if (round < 1) {
            throw new IllegalArgumentException("round must be >= 1");
        }
        if (reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("reasonCode is required");
        }
        Set<String> wanted = new LinkedHashSet<>(unitIds);
        for (String id : wanted) {
            state(id);
        }
        Map<String, List<UnitState>> next = new LinkedHashMap<>();
        for (PreparedDocument d : documents) {
            List<UnitState> list = new ArrayList<>();
            for (UnitState s : states(d.doc().id())) {
                list.add(wanted.contains(s.unit().id()) && !isOriginal(s) ? restored(s, round, reasonCode) : s);
            }
            next.put(d.doc().id(), list);
        }
        LosslessResult result = new LosslessResult(documents, next, warnings, tokenizerId);
        result.verify();
        return result;
    }

    public static final String OP_RESTORE = "restore-only-recovery";

    private static UnitState restored(UnitState s, int round, String reasonCode) {
        String l0 = s.original();
        TransformationRecord before = s.record();
        TransformationRecord rec = new TransformationRecord(s.unit().id(), Action.RESTORED, OP_RESTORE, Level.LOSSLESS,
                reasonCode, before.tokensBefore(), before.tokensBefore(), before.hashBefore(), before.hashBefore(),
                null, round);
        return new UnitState(s.unit(), Action.RESTORED, l0, EditScript.empty(l0.length()), null, null, rec);
    }

    /**
     * Re-checks the guarantees independently of how the result was built.
     *
     * @throws IllegalStateException describing the first violated guarantee
     */
    public void verify() {
        for (PreparedDocument pd : documents) {
            String id = pd.doc().id();
            List<UnitState> list = states(id);
            if (list.size() != pd.units().size()) {
                throw new IllegalStateException("document " + id + ": " + list.size() + " states for "
                        + pd.units().size() + " units");
            }
            for (int i = 0; i < list.size(); i++) {
                if (!list.get(i).unit().equals(pd.units().get(i))) {
                    throw new IllegalStateException("document " + id + ": state " + i + " is for another unit");
                }
                if (!list.get(i).record().action().equals(list.get(i).action())
                        || !list.get(i).record().unitId().equals(list.get(i).unit().id())) {
                    throw new IllegalStateException("record does not match state of " + list.get(i).unit().id());
                }
            }
            if (!reconstruct(id).equals(pd.doc().text())) {
                throw new IllegalStateException("document " + id + " cannot be reconstructed exactly");
            }
            verifyHardSpans(pd, list);
        }
    }

    private void verifyHardSpans(PreparedDocument pd, List<UnitState> list) {
        String text = pd.doc().text();
        for (ProtectedSpan span : pd.hardSpans()) {
            String spanText = text.substring(span.start(), span.end());
            for (UnitState st : list) {
                InformationUnit u = st.unit();
                if (span.end() <= u.start() || u.end() <= span.start()) {
                    continue;
                }
                if (st.merged()) {
                    if (span.start() < u.start() || span.end() > u.end()) {
                        throw new IllegalStateException("HARD span straddles merged unit " + u.id());
                    }
                    if (!state(st.mergedInto()).original().contains(spanText)) {
                        throw new IllegalStateException(
                                "HARD span of " + u.id() + " is not present in surviving unit " + st.mergedInto());
                    }
                    continue;
                }
                for (TextEdit e : st.script().edits()) {
                    int es = u.start() + e.start();
                    int ee = u.start() + e.end();
                    if (es < span.end() && span.start() < ee) {
                        throw new IllegalStateException("edit in " + u.id() + " overlaps HARD span at " + span.start());
                    }
                }
                if (span.start() >= u.start() && span.end() <= u.end()) {
                    int a = st.script().toTransformed(span.start() - u.start());
                    int b = st.script().toTransformed(span.end() - u.start());
                    if (!st.l1().substring(a, b).equals(spanText)) {
                        throw new IllegalStateException("HARD span altered in " + u.id());
                    }
                }
            }
        }
    }
}
