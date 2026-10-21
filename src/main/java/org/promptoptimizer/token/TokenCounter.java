package org.promptoptimizer.token;

/** Counts tokens with a named tokenizer. Implementations must be deterministic. */
public interface TokenCounter {
    TokenCount count(String text);

    String id();
}
