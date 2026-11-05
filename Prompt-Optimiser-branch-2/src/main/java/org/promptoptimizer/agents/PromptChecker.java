package org.promptoptimizer.agents;

import java.io.IOException;
import java.util.Objects;

/** Compares an optimized prompt to its original and restores omitted details. */
public final class PromptChecker {
    private static final String ORIGINAL_PLACEHOLDER = "{{ORG_PROMPT}}";
    private static final String PREVIOUS_PLACEHOLDER = "{{PREV_OPTIMIZED_PROMPT}}";

    private static final String OPTIMIZATION_INSTRUCTIONS = """
            You are a semantic preservation and prompt correction agent.
            
          Your task is to compare an original prompt with its optimized version and ensure that the optimized prompt retains the original meaning, intent, and important details.

          Your goal is to identify meaningful information that has been lost or weakened and restore it without unnecessarily restricting the wording, structure, or conciseness of the optimized prompt.

          ### INPUT

          **ORIGINAL PROMPT:**
          ```text
          {{ORG_PROMPT}}
          ```

          **OPTIMIZED PROMPT:**
          ```text
          {{PREV_OPTIMIZED_PROMPT}}
          ```

          ### INSTRUCTIONS

          1. Carefully compare the original prompt with the optimized prompt to determine whether its important meaning and intent have been preserved.

          2. Focus on semantic preservation rather than preserving the original wording, sentence structure, or organization.

          3. Allow the optimized prompt to rephrase, shorten, combine, or reorganize sentences and requirements when the result communicates the same meaning clearly.

          4. Identify only meaningful information losses or changes, including:
             - Missing requirements or instructions.
             - Weakened constraints or conditions.
             - Changed or omitted numerical values and thresholds.
             - Lost specificity or important technical details.
             - Changes to expected behavior or intended functionality.
             - Missing restrictions, exceptions, or dependencies.
             - Changes to output format or expected deliverables.

          5. Do not treat differences in wording, phrasing, sentence structure, or organization as errors when the original meaning is preserved.

          6. If important information has been removed, weakened, or changed, restore it naturally within the optimized prompt. You may rephrase or reorganize surrounding content to maintain clarity and conciseness.

          7. Do not restore the original wording when the optimized version already communicates the same information.

          8. Do not introduce new requirements, assumptions, examples, explanations, or information that were not present in the original prompt.

          9. Do not unnecessarily rewrite or re-optimize parts of the prompt that are already clear and correct.

          10. Preserve the optimized prompt's existing structure where practical, but allow structural changes when they improve clarity or help restore missing information.

          11. Avoid overcorrection. Do not add details merely because the original prompt expressed them more explicitly if their meaning is already sufficiently conveyed in the optimized version.

          12. If multiple requirements can be expressed more clearly and concisely without losing meaning, you may combine them.

          13. If the optimized prompt preserves all important information, return exactly `{}`.

          14. When corrections are necessary, return the complete corrected optimized prompt, not just the missing information.

          ### OUTPUT FORMAT

          When corrections are required, return only valid JSON in the following format:

          {
            "reasoning": "A brief summary of the meaningful information that was missing, weakened, or changed and the corrections made.",
           "corrected_prompt": "The complete corrected optimized prompt as a string."
           }
    
           When no corrections are required, return exactly:
    
           {}
    
           IMPORTANT OUTPUT RULES
    
           Return only valid JSON.
    
           Use double quotes for JSON keys and string values.
    
           Properly escape quotation marks, newlines, and special characters inside strings.
    
           The corrected_prompt value must contain the complete corrected prompt as a string, not a structured representation of its requirements.
    
           Do not return the prompt as separate TASK, REQUIREMENTS, CONSTRAINTS, or OUTPUT FORMAT JSON fields unless those are literally part of the original prompt's requested output format.
    
           Do not include Markdown code fences around the JSON response.
    
           Do not include introductions, conclusions, or explanations outside the JSON object.
    
           Never return a correction when no meaningful semantic loss has occurred.""";

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
