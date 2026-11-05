package org.promptoptimizer.agents;

import java.io.IOException;
import java.util.Objects;

/** Sends the already-segmented pipeline prompt to the configured Kaggle agent. */
public final class KaggleAgent {
    private final ClassificationAgent classifier;

    private static final String OPTIMIZATION_INSTRUCTIONS = """
            You are a text simplification and prompt optimization agent. Your task is to make the given prompt clearer, shorter, and more direct while preserving its original meaning and intent.

            Follow these rules:

            1. Replace complicated or unnecessarily formal words with simpler, commonly used words.
            2. Remove repeated phrases, redundant explanations, and unnecessary wording.
            3. Shorten long sentences and combine related points where appropriate.
            4. Make instructions clear, direct, and easy to understand.
            5. Condense requirements and constraints when multiple points express similar ideas.
            6. Preserve all important information, requirements, constraints, conditions, and instructions, even when rewriting or combining them.
            7. Preserve technical terms, programming concepts, numbers, and specific conditions wherever necessary.
            8. Do not introduce new requirements, assumptions, or information.
            9. Return the result as a JSON object with exactly these keys: "TASK" (string), "REQUIREMENTS" (array of strings), "CONSTRAINTS" (array of strings), and "OUTPUT FORMAT" (string).
            10. Avoid excessive compression. Prioritize clarity, readability, and meaning over achieving the shortest possible prompt.

            OUTPUT RULES:

            - Return only a valid JSON object matching the specified schema.
            - Do not explain the changes.
            - Do not include introductory or concluding statements.
            - Do not use Markdown fences.
            - Do not repeat JSON keys. Combine related or duplicate requirement items within the "REQUIREMENTS" array.
            - Preserve the original output format instructions in the "OUTPUT FORMAT" value.

            The goal is to produce a concise, clear, and easy-to-understand version of the input JSON. You may rewrite, shorten, combine, or reorganize sentences and bullet points as needed, provided that no important meaning or instruction is lost.
            """;

    public KaggleAgent() throws IOException {
        this(new categorize_agent());
    }

    public KaggleAgent(ClassificationAgent classifier) {
        this.classifier = Objects.requireNonNull(classifier);
    }

    public String optimize(String segmentedPrompt) throws IOException, InterruptedException {
        Objects.requireNonNull(segmentedPrompt);
        String response = classifier.classify(segmentedPrompt, OPTIMIZATION_INSTRUCTIONS);
        return extractResponse(response);
    }

    private static String extractResponse(String json) throws IOException {
        return extractJsonStringField(json, "response");
    }

    static String extractJsonStringField(String json, String field) throws IOException {
        Objects.requireNonNull(json);
        Objects.requireNonNull(field);
        String key = "\"" + field + "\"";
        int keyStart = json.indexOf(key);
        if (keyStart < 0) {
            throw new IOException("Kaggle response did not contain a '" + field + "' field.");
        }
        int start = keyStart + key.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length() || json.charAt(start++) != ':') {
            throw new IOException("Kaggle response contains an invalid '" + field + "' field.");
        }
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        if (start >= json.length() || json.charAt(start++) != '"') {
            throw new IOException("Kaggle response '" + field + "' field must be a string.");
        }

        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char current = json.charAt(i);
            if (escaped) {
                switch (current) {
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'u' -> {
                        if (i + 4 >= json.length()) {
                            throw new IOException("Kaggle response contains an invalid Unicode escape.");
                        }
                        try {
                            result.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                        } catch (NumberFormatException e) {
                            throw new IOException("Kaggle response contains an invalid Unicode escape.", e);
                        }
                        i += 4;
                    }
                    default -> result.append(current);
                }
                escaped = false;
            } else if (current == '\\') {
                escaped = true;
            } else if (current == '"') {
                return result.toString();
            } else {
                result.append(current);
            }
        }
        throw new IOException("Kaggle response contains a malformed '" + field + "' field.");
    }
}
