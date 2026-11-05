package org.promptoptimizer.agents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class KaggleAgentTest {
    @Test
    void readsQuotedEnvValuesWithTrailingSemicolons() {
        Properties env = new Properties();
        env.setProperty("KAGGLE_API_URL", "\"https://example.trycloudflare.com\";");

        assertEquals("https://example.trycloudflare.com",
                categorize_agent.getEnvValue(env, "KAGGLE_API_URL"));
    }

    @Test
    void forwardsThePipelineSplitAndOptimizationInstructionsThenReturnsAgentText() throws Exception {
        String split = "Prompt split:\n[TASK] Write a concise report.\n";
        String[] received = new String[2];
        KaggleAgent agent = new KaggleAgent((prompt, instructions) -> {
            received[0] = prompt;
            received[1] = instructions;
            return "{\"response\":\"Return a short report.\\nKeep the key facts.\"}";
        });

        assertEquals("Return a short report.\nKeep the key facts.", agent.optimize(split));
        assertEquals(split, received[0]);
        assertTrue(received[1].contains("You are a text simplification and prompt optimization agent."));
        assertTrue(received[1].contains("\"REQUIREMENTS\" (array of strings)"));
        assertTrue(received[1].contains("Do not repeat JSON keys."));
    }

    @Test
    void rejectsApiResponsesWithoutResponseField() {
        KaggleAgent agent = new KaggleAgent((prompt, instructions) -> "{\"error\":\"not found\"}");
        assertThrows(IOException.class, () -> agent.optimize("[TASK] Do the work."));
    }
}
