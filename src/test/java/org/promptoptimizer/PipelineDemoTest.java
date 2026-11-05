package org.promptoptimizer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.agents.PromptChecker;
import org.promptoptimizer.agents.PromptChecker.CheckResult;

class PipelineDemoTest {
    @Test
    void stopsFeedbackLoopWhenCheckerReturnsEmptyObject() throws Exception {
        String original = "{\"TASK\":\"Original task\"}";
        String initialOptimized = "{\"TASK\":\"Optimized task\"}";
        List<String> requests = new ArrayList<>();
        PromptChecker checker = new PromptChecker((prompt, instructions) -> {
            requests.add(prompt);
            if (requests.size() == 1) {
                String corrected = "{\"TASK\":\"Restored task detail\"}";
                String agentJson = "{\"reasoning\":\"Restored omitted detail.\",\"corrected_prompt\":\""
                        + escapeJson(corrected) + "\"}";
                return "{\"response\":\"" + escapeJson(agentJson) + "\"}";
            }
            return "{\"response\":\"{}\"}";
        });
        List<CheckResult> replies = new ArrayList<>();

        String result = PipelineDemo.runFeedbackLoop(original, initialOptimized, checker,
                (iteration, reply) -> replies.add(reply));

        assertEquals(2, requests.size());
        assertEquals("{\"TASK\":\"Restored task detail\"}", result);
        assertEquals(2, replies.size());
        assertEquals("{}", replies.get(1).rawAgentOutput());
        assertTrue(replies.get(1).noCorrectionsNeeded());
    }

    @Test
    void feedbackLoopRunsFiveTimesAndFeedsEachReplyIntoTheNextCheck() throws Exception {
        String original = "{\"TASK\":\"Original task\"}";
        String firstOptimized = "{\"TASK\":\"First pass\"}";
        List<String> requests = new ArrayList<>();
        PromptChecker checker = new PromptChecker((prompt, instructions) -> {
            requests.add(prompt);
            String corrected = "{\"TASK\":\"Corrected " + requests.size() + "\"}";
            String agentJson = "{\"reasoning\":\"Preserved all key details.\",\"corrected_prompt\":\""
                    + escapeJson(corrected) + "\"}";
            return "{\"response\":\"" + escapeJson(agentJson) + "\"}";
        });
        List<Integer> printedIterations = new ArrayList<>();
        List<CheckResult> printedReplies = new ArrayList<>();

        String result = PipelineDemo.runFeedbackLoop(original, firstOptimized, checker,
                (iteration, reply) -> {
                    printedIterations.add(iteration);
                    printedReplies.add(reply);
                });

        assertEquals(5, requests.size());
        assertEquals(List.of(1, 2, 3, 4, 5), printedIterations);
        assertEquals(List.of("{\"TASK\":\"Corrected 1\"}", "{\"TASK\":\"Corrected 2\"}",
                "{\"TASK\":\"Corrected 3\"}", "{\"TASK\":\"Corrected 4\"}", "{\"TASK\":\"Corrected 5\"}"),
                printedReplies.stream().map(CheckResult::correctedPrompt).toList());
        assertEquals(result, printedReplies.get(4).correctedPrompt());
        assertEquals("Preserved all key details.", printedReplies.get(0).reasoning());
        assertTrue(printedReplies.get(0).rawAgentOutput().contains("\"reasoning\""));
        for (int i = 0; i < requests.size(); i++) {
            assertTrue(requests.get(i).contains(original));
            assertTrue(requests.get(i).contains(i == 0 ? firstOptimized
                    : printedReplies.get(i - 1).correctedPrompt()));
            assertTrue(requests.get(i).contains("\"reasoning\":"));
            assertTrue(requests.get(i).contains("Do not provide hidden chain-of-thought"));
            assertFalse(requests.get(i).contains("{{ORG_PROMPT}}"));
            assertFalse(requests.get(i).contains("{{PREV_OPTIMIZED_PROMPT}}"));
        }
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }
}
