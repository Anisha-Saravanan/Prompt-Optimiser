package org.promptoptimizer.task;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.promptoptimizer.model.CanonicalDocument;

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
}
