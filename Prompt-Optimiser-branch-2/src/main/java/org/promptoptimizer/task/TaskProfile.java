package org.promptoptimizer.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.promptoptimizer.model.Model.CanonicalDocument;

/**
 * Structured reading of a prompt: what is asked, what is required, what is restricted, and what
 * output is expected, each with provenance. Every item's text is an exact slice of {@code prompt};
 * the constructor enforces this, so profiles cannot contain invented content.
 *
 * @param prompt        the canonical prompt, kept unchanged
 * @param task          the task statement, or null if none could be identified (see warnings)
 * @param requirements  deliverables and conditions, in document order
 * @param constraints   restrictions, prohibitions, preservation requirements, in document order
 * @param outputs       requested output format/structure, in document order
 * @param candidates    cue-bearing text outside labelled sections that was NOT promoted because a
 *                      labelled section of that kind exists (possible repeats; nothing is discarded)
 * @param warnings      extraction limitations and ambiguities
 * @param confidence    overall ordinal confidence
 * @param extractorVersion identifies the profiler implementation that produced the profile
 */
public record TaskProfile(CanonicalDocument prompt, ExtractedItem task, List<ExtractedItem> requirements,
                          List<ExtractedItem> constraints, List<ExtractedItem> outputs,
                          List<ExtractedItem> candidates, List<ProfileWarning> warnings,
                          Confidence confidence, String extractorVersion) {

    public TaskProfile {
        Objects.requireNonNull(prompt);
        Objects.requireNonNull(confidence);
        Objects.requireNonNull(extractorVersion);
        requirements = List.copyOf(requirements);
        constraints = List.copyOf(constraints);
        outputs = List.copyOf(outputs);
        candidates = List.copyOf(candidates);
        warnings = List.copyOf(warnings);
        for (ExtractedItem item : allItems(task, requirements, constraints, outputs, candidates)) {
            verifyItem(prompt, item);
        }
    }

    public Optional<ExtractedItem> taskStatement() {
        return Optional.ofNullable(task);
    }

    private static List<ExtractedItem> allItems(ExtractedItem task, List<ExtractedItem> r, List<ExtractedItem> c,
                                                List<ExtractedItem> o, List<ExtractedItem> x) {
        List<ExtractedItem> all = new ArrayList<>();
        if (task != null) {
            all.add(task);
        }
        all.addAll(r);
        all.addAll(c);
        all.addAll(o);
        all.addAll(x);
        return all;
    }

    private static void verifyItem(CanonicalDocument doc, ExtractedItem item) {
        SourceRef s = item.source();
        if (!s.docId().equals(doc.id()) || s.end() > doc.text().length()) {
            throw new IllegalArgumentException("item " + item.id() + " points outside the prompt");
        }
        if (!s.slice(doc).equals(item.text())) {
            throw new IllegalArgumentException("item " + item.id() + " text differs from its source slice");
        }
    }

    /** Ordinal confidence categories, not calibrated probabilities. */
    public enum Confidence {
        LOW, MEDIUM, HIGH
    }

    /** What role an extracted item plays in the prompt (blueprint section 4, S2 TaskProfile). */
    public enum ItemKind {
        TASK, REQUIREMENT, CONSTRAINT, OUTPUT_EXPECTATION
    }

    /**
     * One item of a {@link TaskProfile}. {@code text} is always an exact slice of the canonical prompt
     * (never paraphrased), so an item cannot be invented.
     *
     * @param id               stable id within the profile (T1, R1, C1, O1, or X1 for unpromoted candidates)
     * @param kind             role of the item
     * @param label            sub-type: constraints use prohibition/limit/preservation/restriction;
     *                         others use task/requirement/format
     * @param text             exact source text
     * @param provenance       where and why
     * @param confidence       ordinal confidence
     * @param duplicateSources other places in the prompt that repeat this item verbatim (normalized)
     */
    public record ExtractedItem(String id, ItemKind kind, String label, String text,
                                Provenance provenance, Confidence confidence,
                                List<SourceRef> duplicateSources) {
        public ExtractedItem {
            Objects.requireNonNull(id);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(label);
            Objects.requireNonNull(text);
            Objects.requireNonNull(provenance);
            Objects.requireNonNull(confidence);
            if (text.isBlank()) {
                throw new IllegalArgumentException("item text must not be blank");
            }
            duplicateSources = List.copyOf(duplicateSources);
        }

        public SourceRef source() {
            return provenance.source();
        }
    }

    /**
     * A transparent note about an extraction limitation. Warnings never change the extracted items;
     * they tell downstream stages (and the user) where to look.
     *
     * @param code     machine-readable reason
     * @param severity INFO (FYI) or WARN (needs attention)
     * @param message  human-readable explanation
     * @param source   related text, or null when the warning concerns the whole prompt
     */
    public record ProfileWarning(Code code, Severity severity, String message, SourceRef source) {

        public enum Severity { INFO, WARN }

        public enum Code {
            EMPTY_INPUT,
            MISSING_TASK,
            AMBIGUOUS_TASK,
            MULTIPLE_TASK_CANDIDATES,
            EMPTY_SECTION,
            DUPLICATE_ITEM,
            /** Cue-bearing sentence outside a labelled section; kept as candidate, not promoted. */
            UNPROMOTED_CANDIDATE,
            /** Cue words occur only inside code, quotes or block quotes; not treated as instructions. */
            CUE_IN_CODE_OR_QUOTE
        }

        public ProfileWarning {
            Objects.requireNonNull(code);
            Objects.requireNonNull(severity);
            Objects.requireNonNull(message);
        }
    }

    /**
     * Evidence for one extracted item: where it came from and how it was identified.
     *
     * @param source the quoted text range
     * @param rule   stable identifier for the extraction method
     * @param basis  classification evidence type
     */
    public record Provenance(SourceRef source, String rule, Basis basis) {

        public enum Basis {
            /** Classification returned by the configured language model. */
            LLM_CLASSIFICATION,
            /** The author labelled the content (e.g. a "CONSTRAINTS:" heading); the item is a quoted span. */
            EXPLICIT_LABEL,
            /** An unlabelled sentence matched a lexical cue (modal, negation, format word, ...). */
            CUE_RULE,
            /** Inferred from position or sentence shape only (e.g. an imperative list item). Least certain. */
            STRUCTURAL_INFERENCE
        }

        public Provenance {
            Objects.requireNonNull(source);
            Objects.requireNonNull(rule);
            Objects.requireNonNull(basis);
        }
    }

    /**
     * A half-open character range [start, end) of a canonical document. Every extracted item points
     * back to the exact text it was taken from.
     *
     * @param docId owning document id
     * @param start inclusive offset into canonical text
     * @param end   exclusive offset into canonical text
     */
    public record SourceRef(String docId, int start, int end) {
        public SourceRef {
            Objects.requireNonNull(docId);
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("invalid source range [" + start + "," + end + ")");
            }
        }

        public String slice(CanonicalDocument doc) {
            return doc.text().substring(start, end);
        }

        public boolean overlaps(SourceRef o) {
            return docId.equals(o.docId) && start < o.end && o.start < end;
        }
    }

    /** Extracts a {@link TaskProfile} from a canonical prompt. */
    public interface TaskProfiler {
        TaskProfile profile(CanonicalDocument prompt);
    }

    public static final class TaskProfilingException extends RuntimeException {
        public TaskProfilingException(String message) {
            super(message);
        }

        public TaskProfilingException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
