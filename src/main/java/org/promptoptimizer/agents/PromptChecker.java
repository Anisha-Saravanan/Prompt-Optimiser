package org.promptoptimizer.agents;

import java.io.IOException;
import java.util.Objects;

/** Compares an optimized prompt to its original and restores omitted details. */
public final class PromptChecker {
    private static final String ORIGINAL_PLACEHOLDER = "{{ORG_PROMPT}}";
    private static final String PREVIOUS_PLACEHOLDER = "{{PREV_OPTIMIZED_PROMPT}}";

    private static final String OPTIMIZATION_INSTRUCTIONS = """
                You are a semantic preservation and prompt correction agent.

                Your task is to compare the original prompt with its previously optimized version. Identify any important information, requirements, constraints, conditions, or instructions that have been removed, weakened, or changed in meaning.

                ### INPUT

                **ORIGINAL PROMPT:**
                ```text
                {{ORG_PROMPT}}
                ```

                **PREVIOUSLY OPTIMIZED PROMPT:**
                ```text
                {{PREV_OPTIMIZED_PROMPT}}
                ```

                ### INSTRUCTIONS

                1. Carefully compare both prompts and check whether the optimized prompt preserves the original meaning and intent.
                2. Ignore differences in wording, sentence structure, or phrasing when the meaning remains the same.
                3. Identify only genuinely missing information or changes that affect the original meaning.
                4. If information is missing, restore it in the appropriate section of the previously optimized prompt.
                5. Do not remove any existing content from the optimized prompt.
                6. Do not unnecessarily rewrite or re-optimize the prompt.
                7. Preserve all existing requirements, constraints, technical terms, numbers, conditions, and output format instructions.
                8. Do not introduce new requirements, assumptions, or information that were not present in the original prompt.
                9. If the optimized prompt already preserves all important information, return it unchanged.
                10. Return the complete corrected prompt, not just the missing information.
                11. If no additions or corrections are required, return exactly {} and nothing else.

                ### OUTPUT FORMAT

                When a correction is required, return only valid JSON in this format:

                {
                "reasoning": "A concise summary of the preservation issues checked and any corrections made. Do not provide hidden chain-of-thought or step-by-step internal reasoning.",
                "corrected_prompt": "The complete corrected optimized prompt as a string."
                }

                When no correction is required, return exactly {}. Do not include explanations or any text outside the JSON object.""";

    private final ClassificationAgent classifier;

    public PromptChecker() throws IOException {
        this(new categorize_agent());
    }

    public PromptChecker(ClassificationAgent classifier) {
        this.classifier = Objects.requireNonNull(classifier);
    }

    public CheckResult check(String originalPrompt, String previousOptimizedPrompt)
            throws IOException, InterruptedException {
        Objects.requireNonNull(originalPrompt);
        Objects.requireNonNull(previousOptimizedPrompt);
        String instructions = OPTIMIZATION_INSTRUCTIONS
                .replace(ORIGINAL_PLACEHOLDER, originalPrompt)
                .replace(PREVIOUS_PLACEHOLDER, previousOptimizedPrompt);
        String response = classifier.generate(instructions);
        String checkerJson = KaggleAgent.extractJsonStringField(response, "response").strip();
        if (checkerJson.startsWith("```")) {
            throw new IOException("Prompt checker returned Markdown fences instead of raw JSON.");
        }
        if (checkerJson.equals("{}")) {
            return new CheckResult("", previousOptimizedPrompt, checkerJson, true);
        }
        String reasoning = KaggleAgent.extractJsonStringField(checkerJson, "reasoning");
        String correctedPrompt = KaggleAgent.extractJsonStringField(checkerJson, "corrected_prompt");
        if (reasoning.isBlank() || correctedPrompt.isBlank()) {
            throw new IOException("Prompt checker returned an empty reasoning or corrected_prompt field.");
        }
        return new CheckResult(reasoning, correctedPrompt, checkerJson, false);
    }

    public record CheckResult(String reasoning, String correctedPrompt, String rawAgentOutput,
                              boolean noCorrectionsNeeded) {
        public CheckResult {
            Objects.requireNonNull(reasoning);
            Objects.requireNonNull(correctedPrompt);
            Objects.requireNonNull(rawAgentOutput);
        }
    }
}
