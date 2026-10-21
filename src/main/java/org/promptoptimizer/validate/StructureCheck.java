package org.promptoptimizer.validate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import org.promptoptimizer.assemble.ProvenanceSegment;
import org.promptoptimizer.assemble.SegmentKind;
import org.promptoptimizer.lossless.DocRole;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.lossless.TextEdit;
import org.promptoptimizer.lossless.WhitespaceNormalizer;

/**
 * Check 6, limited to what this milestone can verify without parsers: file delimiter headers (present once,
 * verbatim id, correct line and omitted-block counts, before the file's first unit), omission markers
 * well-formed and naming an existing block, and text that must never change (non-prose formats and fenced
 * blocks) untouched. JSON/Java/CSV parsing is NOT performed; non-prose documents are instead required to
 * be byte-identical to the original, which implies they parse exactly as the original did.
 */
public final class StructureCheck implements Check {
    @Override
    public CheckId id() {
        return CheckId.STRUCTURE;
    }

    @Override
    public CheckResult run(ValidationContext ctx) {
        OutputIndex ix = OutputIndex.build(ctx);
        List<Finding> f = new ArrayList<>();
        checkHeaders(ix, ctx, f);
        for (OutputIndex.Slot s : ix.slots) {
            ProvenanceSegment seg = s.first();
            if (seg == null) {
                continue;
            }
            if (seg.kind() == SegmentKind.OMISSION_MARKER) {
                checkMarker(ix, s, seg, f);
            }
            checkProtectedFormat(ix, s, seg, f);
        }
        return CheckResult.of(id(), f);
    }

    private void checkHeaders(OutputIndex ix, ValidationContext ctx, List<Finding> f) {
        Map<String, List<ProvenanceSegment>> headerSegs = new HashMap<>();
        Map<String, List<Matcher>> headers = new HashMap<>();
        for (ProvenanceSegment seg : ix.scaffold) {
            String piece = ix.piece(seg);
            if (piece == null) {
                continue;
            }
            Matcher m = OutputIndex.SCAFFOLD_OK.matcher(piece);
            if (m.matches() && m.group(1) != null) {
                headers.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(m);
                headerSegs.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(seg);
            }
        }
        for (PreparedDocument pd : ctx.deliveryOrder()) {
            String id = pd.doc().id();
            List<Matcher> found = headers.getOrDefault(id, List.of());
            if (pd.role() == DocRole.PROMPT) {
                if (!found.isEmpty()) {
                    f.add(err("a file header was emitted for the prompt document '" + id + "'", id));
                }
                continue;
            }
            if (found.size() != 1) {
                f.add(err("file header for '" + id + "' appears " + found.size() + " times, expected exactly once", id));
                continue;
            }
            Matcher m = found.get(0);
            int lines = Integer.parseInt(m.group(2));
            int omitted = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if (lines != OutputIndex.lineCount(pd.doc().text())) {
                f.add(err("header of '" + id + "' says " + lines + " lines, the original has "
                        + OutputIndex.lineCount(pd.doc().text()), id));
            }
            long markers = ix.slots.stream().filter(s -> s.pd == pd && s.first() != null
                    && s.first().kind() == SegmentKind.OMISSION_MARKER).count();
            if (omitted != markers) {
                f.add(err("header of '" + id + "' reports " + omitted + " omitted blocks, the output has " + markers
                        + " markers", id));
            }
            int headerStart = headerSegs.get(id).get(0).outStart();
            for (OutputIndex.Slot s : ix.slots) {
                if (s.pd == pd && s.first() != null && s.first().outStart() < headerStart) {
                    f.add(err("content of '" + id + "' appears before its file header", id));
                    break;
                }
            }
        }
    }

    private void checkMarker(OutputIndex ix, OutputIndex.Slot s, ProvenanceSegment seg, List<Finding> f) {
        String piece = ix.piece(seg);
        if (piece == null || !OutputIndex.MARKER.matcher(piece).matches()) {
            f.add(unit("omission marker of " + s.id() + " is malformed", s));
        } else if (ix.survivorOf(seg) == null) {
            f.add(unit("omission marker of " + s.id() + " names a block that does not exist", s));
        }
    }

    private void checkProtectedFormat(OutputIndex ix, OutputIndex.Slot s, ProvenanceSegment seg, List<Finding> f) {
        if (!s.pd.format().isProse()) {
            if (seg.kind() == SegmentKind.OMISSION_MARKER) {
                f.add(unit("unit " + s.id() + " of a " + s.pd.format() + " document was removed as a duplicate", s));
            } else if (!s.original().equals(ix.piece(seg))) {
                f.add(unit("unit " + s.id() + " of a " + s.pd.format() + " document differs from the original", s));
            }
            return;
        }
        if (seg.kind() != SegmentKind.UNIT || seg.script() == null) {
            return;
        }
        for (int[] fence : WhitespaceNormalizer.fencedRanges(s.pd.doc().text())) {
            for (TextEdit e : seg.script().edits()) {
                int es = s.unit.start() + e.start();
                int ee = s.unit.start() + e.end();
                if (es < fence[1] && fence[0] < ee) {
                    f.add(unit("an edit in " + s.id() + " falls inside a fenced block", s));
                    return;
                }
            }
        }
    }

    private Finding err(String msg, String docId) {
        return new Finding(id(), FailureClass.STRUCT_INVALID, Finding.Severity.ERROR, msg, docId, List.of(), null);
    }

    private Finding unit(String msg, OutputIndex.Slot s) {
        return new Finding(id(), FailureClass.STRUCT_INVALID, Finding.Severity.ERROR, msg, s.unit.docId(),
                List.of(s.id()), null);
    }
}
