package org.promptoptimizer.model;

import java.util.Objects;

/** Core data types of the pipeline, grouped in one file. */
public final class Model {
    private Model() {}

    /** Terminal action recorded for every unit (blueprint section 7.4). */
    public enum Action {
        KEPT, TRANSFORMED, DROPPED, MERGED, RESTORED
    }

    /** Protection level of a span, or lossiness level of an operation. */
    public enum Level {
        /** Must survive byte-identical. Never altered by any operator. */
        HARD,
        /** Should survive; may be merged only with verified duplicates. */
        SOFT,
        /** Operation level: reversible and meaning-preserving. */
        LOSSLESS,
        /** Operation level: near-lossless but not provably equivalent. */
        SOFT_LOSSY,
        /** Operation level: information is removed. */
        LOSSY
    }

    /** Kinds of protected content (blueprint section 5.4). */
    public enum SpanKind {
        CONSTRAINT_SENTENCE,
        NEGATION_OR_MODAL,
        NUMBER_WITH_UNIT,
        IDENTIFIER,
        ERROR_TEXT,
        PATH_OR_URL,
        QUOTED_STRING
    }

    /** Types of information units (blueprint section 5.1). */
    public enum UnitType {
        TASK, REQUIREMENT, CONSTRAINT, INPUT_FACT, ERROR_TEXT, CODE, SCHEMA, DOC,
        EXAMPLE, CONTEXT, REPEAT, NOISE, UNKNOWN
    }

    /**
     * Canonical text of one input (prompt or file): UTF-8 text with LF line endings.
     * The original byte hash is retained so the source stays addressable.
     *
     * @param id             stable id (file name or "prompt")
     * @param text           canonical text
     * @param originalSha256 SHA-256 of the original bytes before normalization
     * @param crlfNormalized true if CRLF/CR line endings were converted to LF (recorded normalization)
     */
    public record CanonicalDocument(String id, String text, String originalSha256, boolean crlfNormalized) {
        public CanonicalDocument {
            Objects.requireNonNull(id);
            Objects.requireNonNull(text);
            Objects.requireNonNull(originalSha256);
        }
    }

    /**
     * An addressable, contiguous slice of a canonical document.
     * Units of one document, in order, must concatenate to exactly the document text
     * (the partition property; see {@code Segmenter.verifyPartition}).
     *
     * @param docId   owning document id
     * @param ordinal position within the document, starting at 0
     * @param start   inclusive offset into canonical text
     * @param end     exclusive offset into canonical text
     * @param type    unit type
     * @param tokens  token count under the counter used (see TokenCount.exact)
     */
    public record InformationUnit(String docId, int ordinal, int start, int end, UnitType type, int tokens) {
        public InformationUnit {
            Objects.requireNonNull(docId);
            Objects.requireNonNull(type);
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("invalid span [" + start + "," + end + ")");
            }
        }

        public String id() {
            return docId + "#" + ordinal;
        }

        public String slice(CanonicalDocument doc) {
            return doc.text().substring(start, end);
        }
    }

    /**
     * A span of canonical text that must be preserved. Offsets are absolute within the document.
     *
     * @param docId  owning document id
     * @param start  inclusive offset
     * @param end    exclusive offset
     * @param kind   what was detected
     * @param level  HARD (never altered) or SOFT
     * @param reason detector rule that fired
     */
    public record ProtectedSpan(String docId, int start, int end, SpanKind kind, Level level, String reason) {
        public ProtectedSpan {
            Objects.requireNonNull(docId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(level);
            if (level != Level.HARD && level != Level.SOFT) {
                throw new IllegalArgumentException("protection level must be HARD or SOFT");
            }
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid span [" + start + "," + end + ")");
            }
        }

        public boolean overlaps(ProtectedSpan o) {
            return docId.equals(o.docId) && start < o.end && o.start < end;
        }
    }
}
