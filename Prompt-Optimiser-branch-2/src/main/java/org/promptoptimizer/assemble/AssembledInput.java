package org.promptoptimizer.assemble;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.promptoptimizer.lossless.EditScript;
import org.promptoptimizer.lossless.LosslessResult.TransformationRecord;

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

    /** The inputs cannot be assembled unambiguously (for example, text collides with the delimiters). */
    public static class AssemblyException extends RuntimeException {
        public AssemblyException(String message) {
            super(message);
        }
    }

    /**
     * Where an output character came from: a range of the original canonical text of {@code docId}.
     * For unchanged characters the range is one character; for characters produced by an edit it is the
     * edited range; for an omission marker it is the whole removed unit.
     */
    public record Origin(String docId, int start, int end, SegmentKind kind) {}

    /**
     * Maps {@code [outStart, outEnd)} of the assembled text to its source. For UNIT segments
     * {@code [origStart, origEnd)} is the unit's range in the original document and {@code script} is its
     * reverse map; for OMISSION_MARKER segments the range is the removed unit; SCAFFOLD segments have
     * no source (docId null, range -1).
     */
    public record ProvenanceSegment(int outStart, int outEnd, SegmentKind kind, String docId, int origStart, int origEnd,
                                    EditScript script) {
        public ProvenanceSegment {
            Objects.requireNonNull(kind);
            if (outStart < 0 || outEnd <= outStart) {
                throw new IllegalArgumentException("invalid output range [" + outStart + "," + outEnd + ")");
            }
            if (kind == SegmentKind.SCAFFOLD) {
                if (docId != null || script != null) {
                    throw new IllegalArgumentException("scaffold has no source");
                }
            } else if (docId == null || origStart < 0 || origEnd < origStart) {
                throw new IllegalArgumentException(kind + " segment needs a source range");
            }
            if (kind == SegmentKind.UNIT && script == null) {
                throw new IllegalArgumentException("UNIT segment needs its edit script");
            }
        }
    }

    /** What a stretch of assembled output is. */
    public enum SegmentKind {
        /** Lossless text of a kept unit. */
        UNIT,
        /** Visible marker standing in for a removed duplicate unit. */
        OMISSION_MARKER,
        /** File delimiters and separators added by the assembler; has no source. */
        SCAFFOLD
    }
}
