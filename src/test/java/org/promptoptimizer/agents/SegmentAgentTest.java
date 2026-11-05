package org.promptoptimizer.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class SegmentAgentTest {
    @Test
    void categorizesPromptWithThreeSectionsAndReturnsAgentJson() throws Exception {
        String prompt = "Build a library. Use Java. Return JSON.";
        String[] received = new String[2];
        String segmented = """
                {"TASK":"Build a library.","REQUIREMENTS":["Return JSON."],"CONSTRAINTS":["Use Java."]}
                """.strip();
        SegmentAgent agent = new SegmentAgent((task, instructions) -> {
            received[0] = task;
            received[1] = instructions;
            return "{\"response\":\"" + escapeJson(segmented) + "\"}";
        });

        assertEquals(segmented, agent.segment(prompt));
        assertEquals(prompt, received[0]);
        assertTrue(received[1].contains("\"TASK\""));
        assertTrue(received[1].contains("\"REQUIREMENTS\""));
        assertTrue(received[1].contains("\"CONSTRAINTS\""));
        assertTrue(received[1].contains("output or formatting instructions"));
        assertTrue(received[1].contains("exactly one category"));
        assertTrue(received[1].contains("Never copy the task statement into requirements, or repeat a requirement as a constraint."));
    }

    @Test
    void rejectsBlankPrompts() {
        SegmentAgent agent = new SegmentAgent((prompt, instructions) -> {
            throw new AssertionError("The classifier should not be called for blank prompts.");
        });
        assertThrows(IllegalArgumentException.class, () -> agent.segment(" \n "));
    }

    @Test
    void rejectsMissingApiResponseField() {
        SegmentAgent agent = new SegmentAgent((prompt, instructions) -> "{\"error\":\"not found\"}");
        assertThrows(IOException.class, () -> agent.segment("Build a library."));
    }

    @Test
    void rejectsMarkdownFencedAgentOutput() {
        SegmentAgent agent = new SegmentAgent((prompt, instructions) ->
                "{\"response\":\"```json\\n{}\\n```\"}");
        assertThrows(IOException.class, () -> agent.segment("Build a library."));
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
