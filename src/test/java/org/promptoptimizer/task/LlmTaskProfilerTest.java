package org.promptoptimizer.task;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.agents.ClassificationAgent;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.task.Provenance.Basis;

class LlmTaskProfilerTest {
    private static final String PROMPT = "Intro.\n\nTASK: Write a report.\n\n"
            + "REQUIREMENTS:\n- Include totals.\n\nCONSTRAINTS:\n- Do not use external tools.\n\n"
            + "Output format: a JSON array.";
    private static final Canonicalizer CANON = new Canonicalizer(100_000);

    private static String response(String... kindAndText) {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < kindAndText.length; i += 2) {
            items.add("{\"kind\":\"" + kindAndText[i] + "\",\"text\":\""
                    + escape(kindAndText[i + 1]) + "\"}");
        }
        return "{\"items\":[" + String.join(",", items) + "]}";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static CanonicalDocument doc(String value) {
        return CANON.canonicalize("prompt", value);
    }

    @Test
    void mapsExistingCategoriesToExactTaskProfileSlices() {
        String task = "Write a report.";
        String requirement = "- Include totals.";
        String constraint = "- Do not use external tools.";
        String output = "a JSON array.";
        LlmTaskProfiler profiler = new LlmTaskProfiler((input, instruction) ->
                response("TASK", task, "REQUIREMENT", requirement, "CONSTRAINT", constraint,
                        "OUTPUT_EXPECTATION", output));

        TaskProfile profile = profiler.profile(doc(PROMPT));

        assertEquals(task, profile.task().text());
        assertEquals(List.of(requirement), profile.requirements().stream().map(ExtractedItem::text).toList());
        assertEquals(List.of(constraint), profile.constraints().stream().map(ExtractedItem::text).toList());
        assertEquals(List.of(output), profile.outputs().stream().map(ExtractedItem::text).toList());
        for (ExtractedItem item : List.of(profile.task(), profile.requirements().get(0),
                profile.constraints().get(0), profile.outputs().get(0))) {
            assertEquals(item.text(), item.source().slice(profile.prompt()));
            assertEquals(Basis.LLM_CLASSIFICATION, item.provenance().basis());
        }
        assertEquals("T1", profile.task().id());
        assertEquals("R1", profile.requirements().get(0).id());
        assertEquals("C1", profile.constraints().get(0).id());
        assertEquals("O1", profile.outputs().get(0).id());
        assertTrue(profile.warnings().isEmpty());
    }

    @Test
    void sendsNormalizedPromptAndEditableInstructionSeparately() {
        AtomicReference<String> receivedPrompt = new AtomicReference<>();
        AtomicReference<String> receivedInstructions = new AtomicReference<>();
        LlmTaskProfiler profiler = new LlmTaskProfiler((input, instructions) -> {
            receivedPrompt.set(input);
            receivedInstructions.set(instructions);
            return "{\"items\":[]}";
        });
        CanonicalDocument normalized = doc("one\r\ntwo");

        profiler.profile(normalized);

        assertEquals(normalized.text(), receivedPrompt.get());
        assertEquals(LlmTaskProfiler.CLASSIFICATION_PROMPT, receivedInstructions.get());
        assertEquals("", LlmTaskProfiler.CLASSIFICATION_PROMPT);
    }

    @Test
    void emptyPromptDoesNotCallAgentAndHasNoUnitsToClassify() {
        LlmTaskProfiler profiler = new LlmTaskProfiler((input, instructions) -> {
            fail("agent must not be called for an empty prompt");
            return "";
        });

        TaskProfile profile = profiler.profile(doc(""));

        assertTrue(profile.taskStatement().isEmpty());
        assertTrue(profile.requirements().isEmpty());
        assertEquals(ProfileWarning.Code.EMPTY_INPUT, profile.warnings().get(0).code());
    }

    @Test
    void rejectsMalformedJsonAndMissingClassificationFields() {
        CanonicalDocument document = doc("Write a report.");
        assertThrows(TaskProfilingException.class,
                () -> new LlmTaskProfiler((input, instructions) -> "{\"items\":[")
                        .profile(document));
        assertThrows(TaskProfilingException.class,
                () -> new LlmTaskProfiler((input, instructions) -> "{\"items\":[{\"kind\":\"TASK\"}]}")
                        .profile(document));
        assertThrows(TaskProfilingException.class,
                () -> new LlmTaskProfiler((input, instructions) -> "{}")
                        .profile(document));
    }

    @Test
    void rejectsUnknownTypesAndTextThatDoesNotExactlyMatchPrompt() {
        CanonicalDocument document = doc("Write a report.");
        assertThrows(TaskProfilingException.class,
                () -> new LlmTaskProfiler((input, instructions) -> response("INPUT_FACT", "Write a report."))
                        .profile(document));
        assertThrows(TaskProfilingException.class,
                () -> new LlmTaskProfiler((input, instructions) -> response("TASK", "write a report"))
                        .profile(document));
        assertThrows(TaskProfilingException.class,
                () -> new LlmTaskProfiler((input, instructions) -> response(
                        "TASK", "report.", "REQUIREMENT", "Write a"))
                        .profile(document));
    }

    @Test
    void apiFailuresAreSurfacedAndInterruptedStatusIsRestored() {
        CanonicalDocument document = doc("Write a report.");
        TaskProfilingException failure = assertThrows(TaskProfilingException.class,
                () -> new LlmTaskProfiler((input, instructions) -> {
                    throw new IOException("unavailable");
                }).profile(document));
        assertInstanceOf(IOException.class, failure.getCause());

        try {
            TaskProfilingException interrupted = assertThrows(TaskProfilingException.class,
                    () -> new LlmTaskProfiler((input, instructions) -> {
                        throw new InterruptedException("cancelled");
                    }).profile(document));
            assertInstanceOf(InterruptedException.class, interrupted.getCause());
            assertTrue(Thread.interrupted());
        } finally {
            Thread.interrupted();
        }
    }
}
