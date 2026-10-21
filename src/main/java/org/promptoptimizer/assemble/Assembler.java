package org.promptoptimizer.assemble;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.promptoptimizer.lossless.DocRole;
import org.promptoptimizer.lossless.LosslessResult;
import org.promptoptimizer.lossless.OmissionMarker;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.lossless.UnitState;
import org.promptoptimizer.model.InformationUnit;
import org.promptoptimizer.token.TokenCounter;

/**
 * Blueprint stage S10, baseline policy only. Layout: the prompt first, then each file in the order
 * given, every file introduced by a delimiter line carrying its verbatim id:
 * <pre>=== FILE: src/Main.java (120 lines; 2 repeated blocks omitted) ===</pre>
 * Units keep their original order. A removed duplicate is replaced by a visible
 * {@code [omitted: ...]} marker. Scaffolding tokens are counted. No reordering, no task
 * duplication, no importance ordering: those are experiments for later milestones.
 *
 * <p>Text that already contains a line starting with a delimiter or marker prefix is rejected
 * rather than escaped, because the output would be ambiguous.
 */
public final class Assembler {
    private static final String FILE_PREFIX = "=== FILE:";
    private static final Pattern COLLISION = Pattern.compile(
            "(?m)^(?:" + Pattern.quote(FILE_PREFIX) + "|" + Pattern.quote(OmissionMarker.PREFIX) + ")");

    private final TokenCounter counter;

    public Assembler(TokenCounter counter) {
        if (counter == null) {
            throw new IllegalArgumentException("counter is required");
        }
        this.counter = counter;
    }

    public AssembledInput assemble(LosslessResult result) {
        if (!counter.id().equals(result.tokenizerId())) {
            throw new IllegalArgumentException("assembler counter '" + counter.id()
                    + "' differs from the lossless pass counter '" + result.tokenizerId() + "'");
        }
        result.verify();

        List<PreparedDocument> prompts = new ArrayList<>();
        List<PreparedDocument> files = new ArrayList<>();
        for (PreparedDocument d : result.documents()) {
            (d.role() == DocRole.PROMPT ? prompts : files).add(d);
        }
        if (prompts.size() > 1) {
            throw new IllegalArgumentException("at most one PROMPT document is supported, got " + prompts.size());
        }

        Map<String, InformationUnit> unitsById = new HashMap<>();
        for (PreparedDocument d : result.documents()) {
            for (UnitState s : result.states(d.doc().id())) {
                unitsById.put(s.unit().id(), s.unit());
                if (!s.merged() && COLLISION.matcher(s.l1()).find()) {
                    throw new AssemblyException("text of unit " + s.unit().id()
                            + " contains a line starting with a reserved delimiter or marker prefix");
                }
            }
        }

        Builder b = new Builder();
        for (PreparedDocument d : prompts) {
            emitUnits(b, d, result, unitsById);
        }
        for (PreparedDocument d : files) {
            if (b.out.length() > 0) {
                b.ensureEndsWith("\n\n");
            }
            long omitted = result.states(d.doc().id()).stream().filter(UnitState::merged).count();
            b.scaffold(header(d, omitted));
            emitUnits(b, d, result, unitsById);
            if (b.out.length() > 0 && b.out.charAt(b.out.length() - 1) != '\n') {
                b.scaffold("\n");
            }
        }

        String text = b.out.toString();
        AssembledInput in = new AssembledInput(text, counter.count(text).tokens(), b.scaffoldTokens,
                result.tokensBefore(), counter.id(), b.segments, result.records());
        in.verifyProvenance();
        return in;
    }

    private void emitUnits(Builder b, PreparedDocument d, LosslessResult result, Map<String, InformationUnit> unitsById) {
        String docId = d.doc().id();
        for (UnitState s : result.states(docId)) {
            InformationUnit u = s.unit();
            if (s.merged()) {
                InformationUnit survivor = unitsById.get(s.mergedInto());
                b.piece(OmissionMarker.exactRepeat(survivor.docId(), survivor.ordinal()), SegmentKind.OMISSION_MARKER,
                        docId, u.start(), u.end(), null);
            } else if (!s.l1().isEmpty()) {
                b.piece(s.l1(), SegmentKind.UNIT, docId, u.start(), u.end(), s.script());
            }
        }
    }

    /** The delimiter line that introduces a file (public so the recovery module can deliver the raw original in the same format). */
    public static String header(PreparedDocument d, long omitted) {
        String text = d.doc().text();
        int lines = 0;
        if (!text.isEmpty()) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    lines++;
                }
            }
            if (text.charAt(text.length() - 1) != '\n') {
                lines++;
            }
        }
        StringBuilder sb = new StringBuilder(FILE_PREFIX).append(' ').append(d.doc().id())
                .append(" (").append(lines).append(lines == 1 ? " line" : " lines");
        if (omitted > 0) {
            sb.append("; ").append(omitted).append(omitted == 1 ? " repeated block omitted" : " repeated blocks omitted");
        }
        return sb.append(") ===\n").toString();
    }

    /** Accumulates output text, segments and scaffold token cost. */
    private final class Builder {
        final StringBuilder out = new StringBuilder();
        final List<ProvenanceSegment> segments = new ArrayList<>();
        int scaffoldTokens;

        void piece(String text, SegmentKind kind, String docId, int origStart, int origEnd,
                   org.promptoptimizer.lossless.EditScript script) {
            int start = out.length();
            out.append(text);
            segments.add(new ProvenanceSegment(start, out.length(), kind, docId, origStart, origEnd, script));
        }

        void scaffold(String text) {
            int start = out.length();
            out.append(text);
            segments.add(new ProvenanceSegment(start, out.length(), SegmentKind.SCAFFOLD, null, -1, -1, null));
            scaffoldTokens += counter.count(text).tokens();
        }

        /** Adds just enough newlines so the output ends with {@code suffix} (only newlines are added). */
        void ensureEndsWith(String suffix) {
            int have = 0;
            while (have < suffix.length() && out.length() > have && out.charAt(out.length() - 1 - have) == '\n') {
                have++;
            }
            if (have < suffix.length()) {
                scaffold("\n".repeat(suffix.length() - have));
            }
        }
    }
}
