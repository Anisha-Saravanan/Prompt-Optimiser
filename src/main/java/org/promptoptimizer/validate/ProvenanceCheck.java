package org.promptoptimizer.validate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import org.promptoptimizer.assemble.AssembledInput;
import org.promptoptimizer.assemble.ProvenanceSegment;
import org.promptoptimizer.assemble.SegmentKind;
import org.promptoptimizer.lossless.Hashes;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.lossless.TransformationRecord;
import org.promptoptimizer.model.Action;

/**
 * Check 10 (plus the S13 audit-record consistency): the candidate's provenance map is verified against
 * the ORIGINAL documents, not against the lossless pass's own state.
 * <ul>
 *   <li>segments tile the text; declared token count and tokenizer id match a recount;</li>
 *   <li>every UNIT segment names exactly one original unit and its reverse map applied to that unit's original
 *       text reproduces the output text exactly;</li>
 *   <li>every original unit is present exactly once (as text or as an omission marker), in original order;</li>
 *   <li>a marker's survivor is a kept unit with the same text (modulo whitespace) as the removed unit;</li>
 *   <li>scaffold segments contain only newlines and well-formed file headers, so nothing was added;</li>
 *   <li>exactly one audit record per unit whose action, hashes and token counts match what the output shows.</li>
 * </ul>
 */
public final class ProvenanceCheck implements Check {
    @Override
    public CheckId id() {
        return CheckId.PROVENANCE;
    }

    @Override
    public CheckResult run(ValidationContext ctx) {
        OutputIndex ix = OutputIndex.build(ctx);
        AssembledInput in = ctx.candidate();
        List<Finding> f = new ArrayList<>();
        checkTiling(in, f);
        if (!in.tokenizerId().equals(ctx.counter().id())) {
            f.add(general("candidate was counted with '" + in.tokenizerId() + "' but validation uses '"
                    + ctx.counter().id() + "'"));
        } else {
            int recount = ctx.counter().count(in.text()).tokens();
            if (recount != in.tokens()) {
                f.add(general("declared token count " + in.tokens() + " differs from the recount " + recount));
            }
        }
        for (ProvenanceSegment o : ix.orphans) {
            f.add(general("segment [" + o.outStart() + "," + o.outEnd() + ") names no original unit exactly (doc "
                    + o.docId() + ", range " + o.origStart() + ".." + o.origEnd() + ")"));
        }
        for (ProvenanceSegment sc : ix.scaffold) {
            String piece = ix.piece(sc);
            if (piece == null) {
                continue;
            }
            Matcher m = OutputIndex.SCAFFOLD_OK.matcher(piece);
            if (!m.matches()) {
                f.add(general("scaffold segment [" + sc.outStart() + "," + sc.outEnd() + ") contains added text: '"
                        + OutputIndex.snippet(piece) + "'"));
            } else if (m.group(1) != null && ctx.originals().stream().noneMatch(d -> d.doc().id().equals(m.group(1)))) {
                f.add(general("file header names an unknown document '" + m.group(1) + "'"));
            }
        }
        checkUnits(ix, f);
        checkRecords(ix, ctx, f);
        return CheckResult.of(id(), f);
    }

    private void checkTiling(AssembledInput in, List<Finding> f) {
        int pos = 0;
        boolean reported = false;
        for (ProvenanceSegment s : in.segments()) {
            if (s.outStart() != pos && !reported) {
                f.add(general("segments leave a gap or overlap at output offset " + pos));
                reported = true;
            }
            pos = s.outEnd();
        }
        if (pos != in.text().length()) {
            f.add(general("segments end at " + pos + " but the output has " + in.text().length() + " characters"));
        }
    }

    private void checkUnits(OutputIndex ix, List<Finding> f) {
        List<Map.Entry<ProvenanceSegment, OutputIndex.Slot>> pairs = new ArrayList<>();
        for (OutputIndex.Slot s : ix.slots) {
            if (s.segments.isEmpty()) {
                if (!s.original().isBlank()) {
                    f.add(unit("unit " + s.id() + " is missing from the output", s));
                }
            } else if (s.segments.size() > 1) {
                f.add(unit("unit " + s.id() + " appears " + s.segments.size() + " times in the output", s));
            }
            for (ProvenanceSegment seg : s.segments) {
                pairs.add(Map.entry(seg, s));
            }
        }
        pairs.sort(Comparator.comparingInt(e -> e.getKey().outStart()));
        int last = -1;
        for (Map.Entry<ProvenanceSegment, OutputIndex.Slot> e : pairs) {
            OutputIndex.Slot s = e.getValue();
            ProvenanceSegment seg = e.getKey();
            if (s.index < last) {
                f.add(unit("unit " + s.id() + " is out of its original order (reordered)", s));
            }
            last = Math.max(last, s.index);
            if (seg.kind() == SegmentKind.UNIT) {
                if (!ix.maps(s, seg)) {
                    f.add(unit("reverse map of " + s.id() + " does not reproduce the output text", s));
                }
            } else {
                OutputIndex.Slot survivor = ix.survivorOf(seg);
                if (survivor == null) {
                    continue; // malformed marker: reported by the structure check
                }
                ProvenanceSegment sseg = survivor.first();
                if (survivor == s || sseg == null || sseg.kind() != SegmentKind.UNIT) {
                    f.add(unit("marker of " + s.id() + " points at " + survivor.id() + ", which is not a kept unit", s));
                } else if (!OutputIndex.duplicateKey(s.original()).equals(OutputIndex.duplicateKey(survivor.original()))) {
                    f.add(unit("marker of " + s.id() + " claims an exact repeat of " + survivor.id()
                            + " but the texts differ", s));
                }
            }
        }
    }

    private void checkRecords(OutputIndex ix, ValidationContext ctx, List<Finding> f) {
        Map<String, List<TransformationRecord>> byUnit = new HashMap<>();
        for (TransformationRecord r : ctx.candidate().records()) {
            byUnit.computeIfAbsent(r.unitId(), k -> new ArrayList<>()).add(r);
            if (!ix.byId.containsKey(r.unitId())) {
                f.add(general("audit record names unknown unit " + r.unitId()));
            }
        }
        int sumBefore = 0;
        for (OutputIndex.Slot s : ix.slots) {
            sumBefore += ctx.counter().count(s.original()).tokens();
            List<TransformationRecord> recs = byUnit.getOrDefault(s.id(), List.of());
            if (recs.size() != 1) {
                f.add(unit("unit " + s.id() + " has " + recs.size() + " audit records, expected exactly 1", s));
                continue;
            }
            String problem = recordProblem(ix, ctx, s, recs.get(0));
            if (problem != null) {
                f.add(unit("audit record of " + s.id() + " is inconsistent: " + problem, s));
            }
        }
        if (ctx.candidate().tokensBefore() != sumBefore) {
            f.add(general("declared tokensBefore " + ctx.candidate().tokensBefore() + " differs from the recount "
                    + sumBefore));
        }
    }

    private String recordProblem(OutputIndex ix, ValidationContext ctx, OutputIndex.Slot s, TransformationRecord r) {
        String original = s.original();
        if (!r.hashBefore().equals(Hashes.sha256(original))) {
            return "hashBefore does not match the original text";
        }
        if (r.tokensBefore() != ctx.counter().count(original).tokens()) {
            return "tokensBefore does not match the original text";
        }
        ProvenanceSegment seg = s.first();
        String piece = seg == null ? null : ix.piece(seg);
        if (seg == null || piece == null) {
            return null; // absence is reported by the unit check
        }
        if (!r.hashAfter().equals(Hashes.sha256(piece))) {
            return "hashAfter does not match the output text";
        }
        if (r.tokensAfter() != ctx.counter().count(piece).tokens()) {
            return "tokensAfter does not match the output text";
        }
        Action a = r.action();
        if (a == Action.MERGED) {
            if (seg.kind() != SegmentKind.OMISSION_MARKER) {
                return "action MERGED but the output holds the unit text";
            }
            OutputIndex.Slot survivor = ix.survivorOf(seg);
            if (survivor != null && !survivor.id().equals(r.relatedUnitId())) {
                return "related unit " + r.relatedUnitId() + " is not the marker's survivor " + survivor.id();
            }
            return null;
        }
        if (seg.kind() != SegmentKind.UNIT) {
            return "action " + a + " but the output holds an omission marker";
        }
        if ((a == Action.KEPT || a == Action.RESTORED) && !piece.equals(original)) {
            return "action " + a + " but the output text differs from the original";
        }
        if (a == Action.TRANSFORMED && piece.equals(original)) {
            return "action TRANSFORMED but the output text equals the original";
        }
        if (a == Action.DROPPED) {
            return "action DROPPED but the unit is present in the output";
        }
        return null;
    }

    private Finding general(String msg) {
        return new Finding(id(), FailureClass.PROVENANCE, Finding.Severity.ERROR, msg, null, List.of(), null);
    }

    private Finding unit(String msg, OutputIndex.Slot s) {
        return new Finding(id(), FailureClass.PROVENANCE, Finding.Severity.ERROR, msg, s.unit.docId(),
                List.of(s.id()), null);
    }
}
