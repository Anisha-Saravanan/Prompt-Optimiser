package org.promptoptimizer.token;


/** Counts tokens with a named tokenizer. Implementations must be deterministic. */
public interface TokenCounter {
    TokenCount count(String text);

    String id();

    /**
     * Result of counting tokens.
     *
     * @param tokens      count (an upper-bound estimate when {@code exact} is false)
     * @param exact       true only when produced by the target model's real tokenizer
     * @param tokenizerId identifier of the counter used; always report it with token metrics
     */
    public record TokenCount(int tokens, boolean exact, String tokenizerId) {}
}
