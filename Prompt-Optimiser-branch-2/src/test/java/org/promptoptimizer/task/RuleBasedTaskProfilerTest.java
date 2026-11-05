package org.promptoptimizer.task;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.task.TaskProfile.Confidence;
import org.promptoptimizer.task.TaskProfile.ItemKind;
import org.promptoptimizer.task.TaskProfile.Provenance.Basis;
import org.promptoptimizer.task.TaskProfile.ProfileWarning.Code;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleBasedTaskProfilerTest {
    private static final Canonicalizer CANON = new Canonicalizer(100_000);
    private final RuleBasedTaskProfiler profiler = new RuleBasedTaskProfiler();

    @Test
    void extractsLabelledSectionsAsExactSourceSlices() {
        String prompt = "TASK: Debug the attached Java project.\n\n"
                + "REQUIREMENTS:\n- Find every compilation error.\n- Explain each root cause.\n\n"
                + "CONSTRAINTS:\n- Keep public signatures unchanged.\n\n"
                + "OUTPUT FORMAT: Return a concise Markdown report.";

        TaskProfile profile = profiler.profile(CANON.canonicalize("prompt", prompt));

        assertEquals("Debug the attached Java project.", profile.task().text());
        assertEquals(List.of("- Find every compilation error.\n- Explain each root cause."),
                profile.requirements().stream().map(item -> item.text()).toList());
        assertEquals("- Keep public signatures unchanged.", profile.constraints().get(0).text());
        assertEquals("Return a concise Markdown report.", profile.outputs().get(0).text());
        assertEquals(Confidence.HIGH, profile.confidence());
        assertTrue(profile.warnings().isEmpty());
        for (var item : List.of(profile.task(), profile.requirements().get(0), profile.constraints().get(0),
                profile.outputs().get(0))) {
            assertEquals(item.text(), item.source().slice(profile.prompt()));
            assertEquals(Basis.EXPLICIT_LABEL, item.provenance().basis());
        }
    }

    @Test
    void recognizesMarkdownLabelsAndDoesNotInventText() {
        String prompt = "## Task\nWrite a short report.\n\n### Constraints\n- Do not change the API.";
        TaskProfile profile = profiler.profile(CANON.canonicalize("prompt", prompt));

        assertEquals(ItemKind.TASK, profile.task().kind());
        assertEquals("Write a short report.", profile.task().text());
        assertEquals("- Do not change the API.", profile.constraints().get(0).text());
        assertEquals(prompt.substring(profile.task().source().start(), profile.task().source().end()),
                profile.task().text());
    }

    @Test
    void infersOnlyAnExplicitImperativeCue() {
        TaskProfile profile = profiler.profile(CANON.canonicalize("prompt",
                "Some background information.\n\nPlease summarize the report in five bullets."));

        assertEquals("Please summarize the report in five bullets.", profile.task().text());
        assertEquals(Basis.CUE_RULE, profile.task().provenance().basis());
        assertEquals(Confidence.MEDIUM, profile.confidence());
        assertEquals(Code.AMBIGUOUS_TASK, profile.warnings().get(0).code());
    }

    @Test
    void reportsMissingTaskWithoutPromotingBackgroundText() {
        TaskProfile profile = profiler.profile(CANON.canonicalize("prompt",
                "Background information only; this paragraph is not an instruction."));

        assertTrue(profile.taskStatement().isEmpty());
        assertEquals(Code.MISSING_TASK, profile.warnings().get(0).code());
        assertEquals(Confidence.LOW, profile.confidence());
    }

    @Test
    void emptyPromptProducesAnExplicitWarning() {
        TaskProfile profile = profiler.profile(CANON.canonicalize("prompt", ""));
        assertEquals(Code.EMPTY_INPUT, profile.warnings().get(0).code());
        assertTrue(profile.taskStatement().isEmpty());
    }
}
