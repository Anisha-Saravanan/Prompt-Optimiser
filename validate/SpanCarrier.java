package org.promptoptimizer.validate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.promptoptimizer.assemble.ProvenanceSegment;
import org.promptoptimizer.assemble.SegmentKind;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.model.ProtectedSpan;

/**
 * Shared logic of checks 2 and 3: every given span of the ORIGINAL text must reappear byte-identical in
 * the candidate. A span inside a kept unit is looked up through that unit's reverse map; a span inside a
 * unit that was replaced by an omission marker must be contained (with multiplicity) in the surviving unit.
 */
final class SpanCarrier {
    private SpanCarrier() {}

    /** Original spans of the context: those the caller supplied plus a fresh run of the detector, de-duplicated. */
    static List<ProtectedSpan> originalSpans(ValidationContext ctx) {
        List<ProtectedSpan> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (PreparedDocument pd : ctx.deliveryOrder()) {
            List<ProtectedSpan> all = new ArrayList<>(pd.spans());
            all.addAll(ctx.detector().detect(pd.doc()));
            for (ProtectedSpan s : all) {
                if (s.end() <= pd.doc().text().length()
                        && seen.add(s.docId() + ":" + s.start() + ":" + s.end() + ":" + s.kind() + ":" + s.level())) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    static List<Finding> verify(OutputIndex ix, List<ProtectedSpan> spans, CheckId check, FailureClass cls, String label) {
        List<Finding> findings = new ArrayList<>();
        Map<OutputIndex.Slot, Map<String, Integer>> mergedNeeds = new LinkedHashMap<>();
        Set<String> reported = new HashSet<>();
        for (ProtectedSpan span : spans) {
            PreparedDocument pd = pdOf(ix, span.docId());
            if (pd == null) {
                continue;
            }
            String spanText = pd.doc().text().substring(span.start(), span.end());
            String what = label + " " + span.kind() + " [" + span.start() + "," + span.end() + ") '" + OutputIndex.snippet(spanText) + "'";
            List<OutputIndex.Slot> over = ix.overlapping(span.docId(), span.start(), span.end());
            if (over.size() == 1) {
                OutputIndex.Slot s = over.get(0);
                ProvenanceSegment seg = s.first();
                if (seg == null) {
                    add(findings, reported, check, cls, what + ": its unit is absent from the output", span.docId(), s.id());
                } else if (seg.kind() == SegmentKind.UNIT) {
                    checkInUnit(ix, s, seg, span, spanText, what, findings, reported, check, cls);
                } else {
                    mergedNeeds.computeIfAbsent(s, k -> new LinkedHashMap<>()).merge(spanText, 1, Integer::sum);
                }
            } else if (over.size() > 1) {
                checkStraddling(ix, over, spanText, what, span.docId(), findings, reported, check, cls);
            }
        }
        for (Map.Entry<OutputIndex.Slot, Map<String, Integer>> e : mergedNeeds.entrySet()) {
            OutputIndex.Slot merged = e.getKey();
            OutputIndex.Slot survivor = ix.survivorOf(merged.first());
            String survivorText = survivor == null || survivor.first() == null || survivor.first().kind() != SegmentKind.UNIT
                    ? null : ix.piece(survivor.first());
            for (Map.Entry<String, Integer> need : e.getValue().entrySet()) {
                String what = label + " '" + OutputIndex.snippet(need.getKey()) + "' of a removed duplicate";
                if (survivorText == null) {
                    add(findings, reported, check, cls, what + ": the omission marker does not identify a kept surviving unit",
                            merged.unit.docId(), merged.id());
                } else if (count(survivorText, need.getKey()) < need.getValue()) {
                    add(findings, reported, check, cls, what + ": not contained " + need.getValue() + "x in surviving unit "
                            + survivor.id(), merged.unit.docId(), merged.id());
                }
            }
        }
        return findings;
    }

    private static void checkInUnit(OutputIndex ix, OutputIndex.Slot s, ProvenanceSegment seg, ProtectedSpan span,
                                    String spanText, String what, List<Finding> findings, Set<String> reported,
                                    CheckId check, FailureClass cls) {
        String piece = ix.piece(seg);
        if (piece == null) {
            add(findings, reported, check, cls, what + ": its segment lies outside the output", span.docId(), s.id());
            return;
        }
        if (ix.maps(s, seg)) {
            try {
                int a = seg.script().toTransformed(span.start() - s.unit.start());
                int b = seg.script().toTransformed(span.end() - s.unit.start());
                String found = piece.substring(a, b);
                if (!found.equals(spanText)) {
                    add(findings, reported, check, cls, what + ": altered, output has '" + OutputIndex.snippet(found) + "'",
                            span.docId(), s.id());
                }
            } catch (IllegalArgumentException e) {
                add(findings, reported, check, cls, what + ": an edit overlaps it", span.docId(), s.id());
            }
        } else if (!piece.contains(spanText)) {
            add(findings, reported, check, cls, what + ": absent from the output of its unit", span.docId(), s.id());
        }
    }

    private static void checkStraddling(OutputIndex ix, List<OutputIndex.Slot> over, String spanText, String what,
                                        String docId, List<Finding> findings, Set<String> reported, CheckId check,
                                        FailureClass cls) {
        List<String> ids = new ArrayList<>();
        int from = Integer.MAX_VALUE;
        int to = -1;
        for (OutputIndex.Slot s : over) {
            ids.add(s.id());
            ProvenanceSegment seg = s.first();
            if (seg == null || seg.kind() != SegmentKind.UNIT || ix.piece(seg) == null) {
                add(findings, reported, check, cls, what + ": a unit it spans is absent or replaced by a marker", docId, ids);
                return;
            }
            from = Math.min(from, seg.outStart());
            to = Math.max(to, seg.outEnd());
        }
        if (!ix.text.substring(from, to).contains(spanText)) {
            add(findings, reported, check, cls, what + ": absent from the output of the units it spans", docId, ids);
        }
    }

    private static PreparedDocument pdOf(OutputIndex ix, String docId) {
        for (PreparedDocument pd : ix.ctx.originals()) {
            if (pd.doc().id().equals(docId)) {
                return pd;
            }
        }
        return null;
    }

    private static void add(List<Finding> findings, Set<String> reported, CheckId check, FailureClass cls, String msg,
                            String docId, String unitId) {
        add(findings, reported, check, cls, msg, docId, List.of(unitId));
    }

    private static void add(List<Finding> findings, Set<String> reported, CheckId check, FailureClass cls, String msg,
                            String docId, List<String> unitIds) {
        if (reported.add(msg)) {
            findings.add(new Finding(check, cls, Finding.Severity.ERROR, msg, docId, unitIds, null));
        }
    }

    /** Non-overlapping occurrences of {@code needle} in {@code hay}. */
    static int count(String hay, String needle) {
        int n = 0;
        int i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) {
            n++;
            i += needle.length();
        }
        return n;
    }
}
