package org.promptoptimizer.validate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.promptoptimizer.assemble.ProvenanceSegment;
import org.promptoptimizer.assemble.SegmentKind;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.model.InformationUnit;

/**
 * Read-only view that lines up the candidate's provenance segments with the ORIGINAL units. It is
 * deliberately tolerant: a unit may have zero, one or several segments, and segments may match no unit;
 * the checks decide what that means.
 */
final class OutputIndex {
    static final Pattern HEADER = Pattern.compile(
            "=== FILE: (.+) \\((\\d+) lines?(?:; (\\d+) repeated blocks? omitted)?\\) ===\\n");
    static final Pattern SCAFFOLD_OK = Pattern.compile(
            "\\n*(?:=== FILE: (.+) \\((\\d+) lines?(?:; (\\d+) repeated blocks? omitted)?\\) ===\\n)?\\n*");
    static final Pattern MARKER = Pattern.compile("\\[omitted: exact repeat of block (\\d+) of (.+)\\]\\n\\n");

    /** One original unit and the candidate segments that claim it. */
    static final class Slot {
        final PreparedDocument pd;
        final InformationUnit unit;
        final int index;
        final List<ProvenanceSegment> segments = new ArrayList<>();

        Slot(PreparedDocument pd, InformationUnit unit, int index) {
            this.pd = pd;
            this.unit = unit;
            this.index = index;
        }

        String id() {
            return unit.id();
        }

        String original() {
            return pd.doc().text().substring(unit.start(), unit.end());
        }

        /** The first segment claiming this unit, or null when the unit is absent. */
        ProvenanceSegment first() {
            return segments.isEmpty() ? null : segments.get(0);
        }
    }

    final ValidationContext ctx;
    final String text;
    final List<Slot> slots = new ArrayList<>();
    final Map<String, Slot> byId = new HashMap<>();
    /** UNIT or OMISSION_MARKER segments that match no original unit exactly. */
    final List<ProvenanceSegment> orphans = new ArrayList<>();
    final List<ProvenanceSegment> scaffold = new ArrayList<>();

    private OutputIndex(ValidationContext ctx) {
        this.ctx = ctx;
        this.text = ctx.candidate().text();
    }

    static OutputIndex build(ValidationContext ctx) {
        OutputIndex ix = new OutputIndex(ctx);
        Map<String, Slot> byRange = new HashMap<>();
        for (PreparedDocument pd : ctx.deliveryOrder()) {
            for (InformationUnit u : pd.units()) {
                Slot s = new Slot(pd, u, ix.slots.size());
                ix.slots.add(s);
                ix.byId.put(u.id(), s);
                byRange.put(pd.doc().id() + "\u0000" + u.start() + "\u0000" + u.end(), s);
            }
        }
        for (ProvenanceSegment seg : ctx.candidate().segments()) {
            if (seg.kind() == SegmentKind.SCAFFOLD) {
                ix.scaffold.add(seg);
                continue;
            }
            Slot s = byRange.get(seg.docId() + "\u0000" + seg.origStart() + "\u0000" + seg.origEnd());
            if (s == null) {
                ix.orphans.add(seg);
            } else {
                s.segments.add(seg);
            }
        }
        return ix;
    }

    /** Output text of a segment, or null if its range lies outside the output text. */
    String piece(ProvenanceSegment seg) {
        if (seg.outEnd() > text.length()) {
            return null;
        }
        return text.substring(seg.outStart(), seg.outEnd());
    }

    /** True if the UNIT segment's reverse map applied to the ORIGINAL unit text gives exactly the output piece. */
    boolean maps(Slot slot, ProvenanceSegment seg) {
        if (seg.kind() != SegmentKind.UNIT || seg.script() == null) {
            return false;
        }
        String piece = piece(seg);
        if (piece == null) {
            return false;
        }
        try {
            return seg.script().apply(slot.original()).equals(piece);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** The slot named by a marker segment's text, or null if the marker is malformed or names no unit. */
    Slot survivorOf(ProvenanceSegment marker) {
        String piece = piece(marker);
        if (piece == null) {
            return null;
        }
        Matcher m = MARKER.matcher(piece);
        if (!m.matches()) {
            return null;
        }
        int block;
        try {
            block = Integer.parseInt(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
        return byId.get(m.group(2) + "#" + (block - 1));
    }

    /** Slots of {@code docId} whose unit range overlaps [start, end). */
    List<Slot> overlapping(String docId, int start, int end) {
        List<Slot> out = new ArrayList<>();
        for (Slot s : slots) {
            if (s.unit.docId().equals(docId) && s.unit.start() < end && start < s.unit.end()) {
                out.add(s);
            }
        }
        return out;
    }

    static int lineCount(String docText) {
        int lines = 0;
        for (int i = 0; i < docText.length(); i++) {
            if (docText.charAt(i) == '\n') {
                lines++;
            }
        }
        if (!docText.isEmpty() && docText.charAt(docText.length() - 1) != '\n') {
            lines++;
        }
        return lines;
    }

    /** Whitespace-insensitive form used to judge that two units are exact repeats of each other. */
    static String duplicateKey(String s) {
        String t = s.replaceAll("[ \\t]+(?=\\n|\\z)", "");
        t = t.replaceAll("\\n{3,}", "\n\n");
        return t.stripTrailing();
    }

    static String snippet(String s) {
        String t = s.replace("\n", "\\n");
        return t.length() <= 60 ? t : t.substring(0, 57) + "...";
    }
}
