package org.promptoptimizer.agents;

import java.io.IOException;

@FunctionalInterface
public interface ClassificationAgent {
    String classify(String normalizedPrompt, String instructionPrompt) throws IOException, InterruptedException;

    default String generate(String prompt) throws IOException, InterruptedException {
        return classify(prompt, "");
    }
}
