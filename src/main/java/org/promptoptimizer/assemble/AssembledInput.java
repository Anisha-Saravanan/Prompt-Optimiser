package org.promptoptimizer.assemble;

import java.util.List;
import java.util.Optional;
import org.promptoptimizer.lossless.EditScript;
import org.promptoptimizer.lossless.TransformationRecord;

/**
 * The single text to send downstream, with its token count, the provenance map from output
 * characters back to original offsets, and the per-unit audit records.
 *
 * @param text          assembled text
 * @param tokens        tokens of the whole text, scaffolding included (one counter call)
 * @param scaffoldTokens tokens of delimiters and separators, counted piece by piece (informational)
 * @param tokensBefore  tokens of the original units before any change, without scaffolding
 * @param tokenizerId   counter used; token fields are only comparable under the same id
 */
public record AssembledInput(String text, int tokens, int scaffoldTokens, int tokensBefore, String tokenizerId,
                             List<ProvenanceSegment> segments, List<TransformationRecord> records) {
    public AssembledInput {
        segments = List.copyOf(segments);
        records = List.copyOf(records);
    }

    /** Tokens added (positive) or saved (negative) relative to the original units, scaffolding included. */
    public int netTokenChange() {
        return tokens - tokensBefore;
    }

    /** Source of the character at {@code outOffset}; empty for scaffolding. */
    public Optional<Origin> origin(int outOffset) {
        if (outOffset < 0 || outOffset >= text.length()) {
            throw new IllegalArgumentException("offset out of range: " + outOffset);
        }
        for (ProvenanceSegment s : segments) {
            if (outOffset < s.outStart() || outOffset >= s.outEnd()) {
                continue;
            }
            switch (s.kind()) {
                case SCAFFOLD:
                    return Optional.empty();
                case OMISSION_MARKER:
                    return Optional.of(new Origin(s.docId(), s.origStart(), s.origEnd(), s.kind()));
                default:
                    EditScript.Range r = s.script().originRange(outOffset - s.outStart());
                    return Optional.of(new Origin(s.docId(), s.origStart() + r.start(), s.origStart() + r.end(), s.kind()));
            }
        }
        throw new IllegalStateException("no segment covers offset " + outOffset);
    }

    /**
     * Checks that segments tile the text exactly and that each UNIT segment has the length its
     * reverse map expects.
     *
     * @throws IllegalStateException describing the first violation
     */
    public void verifyProvenance() {
        int pos = 0;
        for (ProvenanceSegment s : segments) {
            if (s.outStart() != pos) {
                throw new IllegalStateException("gap or overlap at output offset " + pos);
            }
            if (s.kind() == SegmentKind.UNIT && s.script().transformedLength() != s.outEnd() - s.outStart()) {
                throw new IllegalStateException("UNIT segment at " + s.outStart() + " does not match its reverse map");
            }
            pos = s.outEnd();
        }
        if (pos != text.length()) {
            throw new IllegalStateException("segments end at " + pos + " but text length is " + text.length());
        }
    }
}
