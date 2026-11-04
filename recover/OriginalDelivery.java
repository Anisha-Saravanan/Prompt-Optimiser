package org.promptoptimizer.recover;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.promptoptimizer.assemble.AssembledInput;
import org.promptoptimizer.assemble.Assembler;
import org.promptoptimizer.assemble.ProvenanceSegment;
import org.promptoptimizer.assemble.SegmentKind;
import org.promptoptimizer.lossless.DocRole;
import org.promptoptimizer.lossless.Hashes;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.lossless.TransformationRecord;
import org.promptoptimizer.model.Action;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.model.Level;
import org.promptoptimizer.token.TokenCounter;

/**
 * The original input exactly as it would be delivered with no operator applied: the prompt first, then each file
 * under the same delimiter line the assembler uses. Built without the lossless pass and without the assembler, so
 * it still works when those fail. For a single prompt document it is byte-identical to the prompt text.
 */
final class OriginalDelivery {
    private OriginalDelivery() {}

    static String text(List<PreparedDocument> docs) {
        StringBuilder out = new StringBuilder();
        for (PreparedDocument d : docs) {
            if (d.role() == DocRole.PROMPT) {
                out.append(d.doc().text());
            }
        }
        for (PreparedDocument d : docs) {
            if (d.role() == DocRole.PROMPT) {
                continue;
            }
            if (out.length() > 0) {
                int have = 0;
                while (have < 2 && out.length() > have && out.charAt(out.length() - 1 - have) == '\n') {
                    have++;
                }
                out.append("\n".repeat(2 - have));
            }
            out.append(Assembler.header(d, 0)).append(d.doc().text());
            if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
        }
        return out.toString();
    }

    /** One KEPT record per unit: the audit of "nothing was changed". */
    static List<TransformationRecord> records(List<PreparedDocument> docs, TokenCounter counter, String reason) {
        List<TransformationRecord> out = new ArrayList<>();
        for (PreparedDocument d : docs) {
            for (InformationUnit u : d.units()) {
                String t = d.doc().text().substring(u.start(), u.end());
                int tokens = counter.count(t).tokens();
                String h = Hashes.sha256(t);
                out.add(new TransformationRecord(u.id(), Action.KEPT, "none", Level.LOSSLESS, reason, tokens, tokens, h, h,
                        null, 0));
            }
        }
        return out;
    }

    /** Piece-wise token estimate by unit type; file delimiter lines are counted as SCAFFOLD. */
    static Map<String, Integer> composition(List<PreparedDocument> docs, TokenCounter counter) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (PreparedDocument d : docs) {
            for (InformationUnit u : d.units()) {
                m.merge(u.type().name(), counter.count(d.doc().text().substring(u.start(), u.end())).tokens(), Integer::sum);
            }
            if (d.role() != DocRole.PROMPT) {
                m.merge("SCAFFOLD", counter.count(Assembler.header(d, 0)).tokens(), Integer::sum);
            }
        }
        return m;
    }

    /** Piece-wise token estimate of an assembled candidate by unit type, SCAFFOLD and OMISSION_MARKER. */
    static Map<String, Integer> composition(AssembledInput in, List<PreparedDocument> docs, TokenCounter counter) {
        Map<String, InformationUnit> byRange = new LinkedHashMap<>();
        for (PreparedDocument d : docs) {
            for (InformationUnit u : d.units()) {
                byRange.put(d.doc().id() + "\u0000" + u.start() + "\u0000" + u.end(), u);
            }
        }
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ProvenanceSegment s : in.segments()) {
            String piece = in.text().substring(s.outStart(), s.outEnd());
            String key;
            if (s.kind() == SegmentKind.SCAFFOLD) {
                key = "SCAFFOLD";
            } else if (s.kind() == SegmentKind.OMISSION_MARKER) {
                key = "OMISSION_MARKER";
            } else {
                InformationUnit u = byRange.get(s.docId() + "\u0000" + s.origStart() + "\u0000" + s.origEnd());
                key = u == null ? "UNKNOWN" : u.type().name();
            }
            m.merge(key, counter.count(piece).tokens(), Integer::sum);
        }
        return m;
    }
}
