package org.promptoptimizer.lossless;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.promptoptimizer.lossless.EditScript.TextEdit;
import org.promptoptimizer.model.Model.Action;
import org.promptoptimizer.model.Model.CanonicalDocument;
import org.promptoptimizer.model.Model.InformationUnit;
import org.promptoptimizer.model.Model.Level;
import org.promptoptimizer.model.Model.ProtectedSpan;
import org.promptoptimizer.segment.Segmenter;

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
    public static final String OP_DROP = "typed-budget-selection";

    /**
     * Returns a new result in which the named units are omitted from delivery but retain their L1 text and
     * reverse maps for restore-only recovery. A unit containing a HARD span cannot be dropped.
     *
     * @throws IllegalArgumentException for an unknown unit, a blank reason, a merged unit, or a HARD-span unit
     */
    public LosslessResult withDropped(Collection<String> unitIds, String reasonCode) {
        if (unitIds == null || reasonCode == null || reasonCode.isBlank()) {
            throw new IllegalArgumentException("unit ids and reasonCode are required");
        }
        Set<String> wanted = new LinkedHashSet<>(unitIds);
        for (String id : wanted) {
            UnitState state = state(id);
            if (state.merged()) {
                throw new IllegalArgumentException("cannot select an already merged unit out of delivery: " + id);
            }
            PreparedDocument document = documents.stream()
                    .filter(d -> d.doc().id().equals(state.unit().docId())).findFirst().orElseThrow();
            for (ProtectedSpan span : document.hardSpans()) {
                if (span.start() < state.unit().end() && state.unit().start() < span.end()) {
                    throw new IllegalArgumentException("cannot drop unit " + id + " because it contains a HARD span");
                }
            }
        }
        Map<String, List<UnitState>> next = new LinkedHashMap<>();
        for (PreparedDocument document : documents) {
            List<UnitState> list = new ArrayList<>();
            for (UnitState state : states(document.doc().id())) {
                list.add(wanted.contains(state.unit().id()) ? dropped(state, reasonCode) : state);
            }
            next.put(document.doc().id(), list);
        }
        LosslessResult result = new LosslessResult(documents, next, warnings, tokenizerId);
        result.verify();
        return result;
    }

    private static UnitState dropped(UnitState state, String reasonCode) {
        TransformationRecord before = state.record();
        TransformationRecord record = new TransformationRecord(state.unit().id(), Action.DROPPED, OP_DROP, Level.LOSSY,
                reasonCode, before.tokensBefore(), 0, before.hashBefore(), Hashes.sha256(""), null, 0);
        return new UnitState(state.unit(), Action.DROPPED, state.l1(), state.script(), null, null, record);
    }

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
                if (st.action() == Action.DROPPED) {
                    throw new IllegalStateException("unit " + u.id() + " with a HARD span was dropped");
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

    /**
     * Audit record: exactly one per unit, holding its terminal action (blueprint section 7.4).
     * Differences from the blueprint's sketch: ids are the existing string unit ids (no UUIDs, no run
     * concept yet), and the later-added lexical scores are not snapshotted in this record.
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

    /**
     * Current representation of one unit. L0 is recoverable from L1 and {@code script}; a MERGED unit
     * also stores its removed original and is represented by an omission marker. A DROPPED unit retains
     * its current L1 and reverse map for recovery but is not emitted by the assembler.
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

    /**
     * A canonical document together with its segmentation and protected spans: the input of the
     * lossless pass. The constructor verifies the partition property and that every span belongs to
     * the document and lies within it.
     */
    public record PreparedDocument(CanonicalDocument doc, DocRole role, ContentFormat format,
                                   List<InformationUnit> units, List<ProtectedSpan> spans) {
        public PreparedDocument {
            Objects.requireNonNull(doc);
            Objects.requireNonNull(role);
            Objects.requireNonNull(format);
            units = List.copyOf(units);
            spans = List.copyOf(spans);
            Segmenter.verifyPartition(doc, units);
            if (doc.text().isEmpty() && !units.isEmpty()) {
                throw new IllegalArgumentException("empty document must have no units");
            }
            for (ProtectedSpan s : spans) {
                if (!s.docId().equals(doc.id())) {
                    throw new IllegalArgumentException("span belongs to another document");
                }
                if (s.end() > doc.text().length()) {
                    throw new IllegalArgumentException("span exceeds document length");
                }
            }
        }

        public List<ProtectedSpan> hardSpans() {
            List<ProtectedSpan> hard = new ArrayList<>();
            for (ProtectedSpan s : spans) {
                if (s.level() == Level.HARD) {
                    hard.add(s);
                }
            }
            return hard;
        }
    }

    /** Whether a document is the user's prompt or an attached file. Prompt units win duplicate ties. */
    public enum DocRole {
        PROMPT, FILE
    }

    /**
     * Declared format of a document. The caller states it because no file-type detection exists yet
     * (blueprint S0/S1 extractors are a later milestone). It decides which lossless operations are
     * allowed: whitespace and line structure carry meaning in code, logs and structured data, so those
     * formats are never touched by this milestone.
     */
    public enum ContentFormat {
        PLAIN_TEXT(true),
        MARKDOWN(true),
        SOURCE_CODE(false),
        LOG(false),
        STRUCTURED_DATA(false);

        private final boolean prose;

        ContentFormat(boolean prose) {
            this.prose = prose;
        }

        /** True if whitespace normalization and exact-duplicate removal may be applied (outside fenced blocks). */
        public boolean isProse() {
            return prose;
        }
    }

    /** SHA-256 helper for unit hashes. */
    public static final class Hashes {
        private Hashes() {}

        public static String sha256(String text) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
    }

    /**
     * The visible marker that stands in for a removed exact-duplicate unit (blueprint S10: omissions
     * must be visible so the model knows content is missing). Lives here because the lossless pass has
     * to know the marker's token cost before deciding that a removal is worth it.
     */
    public static final class OmissionMarker {
        public static final String PREFIX = "[omitted:";

        private OmissionMarker() {}

        /** Marker line plus the blank line that separates it from the next block. */
        public static String exactRepeat(String survivorDocId, int survivorOrdinal) {
            return PREFIX + " exact repeat of block " + (survivorOrdinal + 1) + " of " + survivorDocId + "]\n\n";
        }
    }
}
