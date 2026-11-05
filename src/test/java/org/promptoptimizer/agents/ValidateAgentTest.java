package org.promptoptimizer.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ValidateAgentTest {
    @Test
    void executesPromptWithInstructionsAndReturnsAgentText() throws Exception {
        String prompt = "Write a short report.";
        String[] received = new String[2];
        ValidateAgent agent = new ValidateAgent((task, instructions) -> {
            received[0] = task;
            received[1] = instructions;
            return "{\"response\":\"Report complete.\\nKey facts included.\"}";
        });

        assertEquals("Report complete.\nKey facts included.", agent.execute(prompt));
        assertEquals(prompt, received[0]);
        assertTrue(received[1].contains("Execute the prompt supplied below"));
        assertTrue(received[1].contains("Do not rewrite, optimize, critique, or summarize"));
    }

    @Test
    void rejectsBlankPrompts() {
        ValidateAgent agent = new ValidateAgent((prompt, instructions) -> {
            throw new AssertionError("The classifier should not be called for blank prompts.");
        });
        assertThrows(IllegalArgumentException.class, () -> agent.execute(" \n "));
    }

    @Test
    void rejectsApiResponsesWithoutResponseField() {
        ValidateAgent agent = new ValidateAgent((prompt, instructions) -> "{\"error\":\"not found\"}");
        assertThrows(IOException.class, () -> agent.execute("Do the work."));
    }
}
