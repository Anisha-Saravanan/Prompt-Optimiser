package org.promptoptimizer.validate;

import java.util.ArrayList;
import java.util.List;
import org.promptoptimizer.assemble.ProvenanceSegment;
import org.promptoptimizer.assemble.SegmentKind;
import org.promptoptimizer.task.ExtractedItem;
import org.promptoptimizer.task.TaskProfile;

/**
 * Check 4, deterministic part only: every TaskProfile item (task, requirements, constraints, output
 * expectations) must be carried VERBATIM by the output, either in its own kept unit or, if its unit was
 * removed as an exact duplicate, by the surviving unit. There is no entailment verifier, so a carrier
 * that was edited, even by a lossless whitespace edit inside the item, is reported rather than accepted.
 * Unpromoted candidates (X*) are checked at WARNING severity. Every item is treated as mandatory
 * regardless of its confidence (conservative; the blueprint's IMPORTANT/tau_req tier needs importance scores).
 */
public final class RequirementCheck implements Check {
    @Override
    public CheckId id() {
        return CheckId.REQUIREMENTS;
    }

    @Override
    public CheckResult run(ValidationContext ctx) {
        TaskProfile p = ctx.profile();
        if (p == null) {
            return CheckResult.notApplicable(id(), "no TaskProfile supplied");
        }
        OutputIndex ix = OutputIndex.build(ctx);
        List<Finding> findings = new ArrayList<>();
        List<ExtractedItem> mandatory = new ArrayList<>();
        p.taskStatement().ifPresent(mandatory::add);
        mandatory.addAll(p.requirements());
        mandatory.addAll(p.constraints());
        mandatory.addAll(p.outputs());
        for (ExtractedItem item : mandatory) {
            checkItem(ix, item, Finding.Severity.ERROR, findings);
        }
        for (ExtractedItem item : p.candidates()) {
            checkItem(ix, item, Finding.Severity.WARNING, findings);
        }
        return CheckResult.of(id(), findings);
    }

    private void checkItem(OutputIndex ix, ExtractedItem item, Finding.Severity severity, List<Finding> findings) {
        var src = item.source();
        List<OutputIndex.Slot> over = ix.overlapping(src.docId(), src.start(), src.end());
        List<String> ids = new ArrayList<>();
        over.forEach(s -> ids.add(s.id()));
        String reason = null;
        StringBuilder carried = new StringBuilder();
        for (OutputIndex.Slot s : over) {
            ProvenanceSegment seg = s.first();
            int a = Math.max(src.start(), s.unit.start()) - s.unit.start();
            int b = Math.min(src.end(), s.unit.end()) - s.unit.start();
            String portion = s.original().substring(a, b);
            if (seg == null) {
                reason = "its carrier unit " + s.id() + " is absent from the output";
            } else if (seg.kind() == SegmentKind.UNIT) {
                String piece = ix.piece(seg);
                if (piece == null) {
                    reason = "the segment of " + s.id() + " lies outside the output";
                } else if (ix.maps(s, seg) && editInside(seg, a, b)) {
                    reason = "a lossless edit falls inside the item text in " + s.id()
                            + " (verbatim carrier required; no entailment verifier exists)";
                } else if (ix.maps(s, seg)) {
                    try {
                        carried.append(piece, seg.script().toTransformed(a), seg.script().toTransformed(b));
                    } catch (IllegalArgumentException e) {
                        reason = "a lossless edit falls inside the item text in " + s.id()
                                + " (verbatim carrier required; no entailment verifier exists)";
                    }
                } else if (piece.contains(portion)) {
                    carried.append(portion);
                } else {
                    reason = "its text is absent from the output of " + s.id();
                }
            } else {
                OutputIndex.Slot surv = ix.survivorOf(seg);
                ProvenanceSegment sseg = surv == null ? null : surv.first();
                String spiece = sseg == null || sseg.kind() != SegmentKind.UNIT ? null : ix.piece(sseg);
                if (spiece != null && spiece.contains(portion)) {
                    carried.append(portion);
                } else {
                    reason = "its unit " + s.id() + " was removed as a duplicate but the surviving unit does not contain the text";
                }
            }
            if (reason != null) {
                break;
            }
        }
        if (reason == null && !carried.toString().equals(item.text())) {
            reason = "output text '" + OutputIndex.snippet(carried.toString()) + "' differs from the item text";
        }
        if (reason != null) {
            findings.add(new Finding(id(), FailureClass.REQ_MISSING, severity, item.kind() + " item " + item.id()
                    + " '" + OutputIndex.snippet(item.text()) + "' is not carried verbatim: " + reason,
                    src.docId(), ids, item.id()));
        }
    }

    /** True if an edit of the unit's reverse map changes text inside [a, b) of the original unit. */
    private static boolean editInside(ProvenanceSegment seg, int a, int b) {
        for (org.promptoptimizer.lossless.TextEdit e : seg.script().edits()) {
            if (e.start() < b && a < e.end()) {
                return true;
            }
        }
        return false;
    }
}
