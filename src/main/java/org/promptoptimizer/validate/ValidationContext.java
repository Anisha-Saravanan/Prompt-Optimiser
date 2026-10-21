package org.promptoptimizer.validate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.promptoptimizer.assemble.AssembledInput;
import org.promptoptimizer.lossless.DocRole;
import org.promptoptimizer.lossless.PreparedDocument;
import org.promptoptimizer.protect.ProtectedSpanDetector;
import org.promptoptimizer.task.TaskProfile;
import org.promptoptimizer.token.TokenCounter;

/**
 * Everything a validation run reads. The originals are the prepared documents as they were BEFORE any
 * operator ran; the validator never reads the lossless pass's own state, so it checks the candidate
 * independently of how the candidate was built.
 *
 * @param profile  task profile of the prompt document, or null (requirement check is then NOT_APPLICABLE)
 * @param budget   token budget, or null for none (budget check is then NOT_APPLICABLE)
 * @param detector re-run on the original documents; its spans are added to the spans the caller supplied
 */
public record ValidationContext(List<PreparedDocument> originals, TaskProfile profile, Integer budget,
                                TokenCounter counter, ProtectedSpanDetector detector, AssembledInput candidate) {
    public ValidationContext {
        Objects.requireNonNull(originals);
        Objects.requireNonNull(counter);
        Objects.requireNonNull(detector);
        Objects.requireNonNull(candidate);
        originals = List.copyOf(originals);
        if (budget != null && budget < 0) {
            throw new IllegalArgumentException("budget must be >= 0");
        }
        Set<String> ids = new HashSet<>();
        int prompts = 0;
        for (PreparedDocument d : originals) {
            if (!ids.add(d.doc().id())) {
                throw new IllegalArgumentException("duplicate document id: " + d.doc().id());
            }
            if (d.role() == DocRole.PROMPT) {
                prompts++;
            }
        }
        if (prompts > 1) {
            throw new IllegalArgumentException("at most one PROMPT document is supported, got " + prompts);
        }
        if (profile != null) {
            boolean match = originals.stream().anyMatch(d -> d.role() == DocRole.PROMPT
                    && d.doc().id().equals(profile.prompt().id()) && d.doc().text().equals(profile.prompt().text()));
            if (!match) {
                throw new IllegalArgumentException("the task profile was not built from the PROMPT document");
            }
        }
    }

    public ValidationContext withCandidate(AssembledInput other) {
        return new ValidationContext(originals, profile, budget, counter, detector, other);
    }

    /** Documents in the order the baseline assembly delivers them: the prompt first, then files in the given order. */
    public List<PreparedDocument> deliveryOrder() {
        List<PreparedDocument> out = new ArrayList<>();
        originals.stream().filter(d -> d.role() == DocRole.PROMPT).forEach(out::add);
        originals.stream().filter(d -> d.role() != DocRole.PROMPT).forEach(out::add);
        return out;
    }
}
