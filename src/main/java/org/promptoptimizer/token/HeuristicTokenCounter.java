package org.promptoptimizer.token;

/**
 * Conservative fallback counter. It is NOT a real tokenizer: it estimates an upper bound so that
 * budget checks err on the safe side, and it always reports {@code exact = false}.
 *
 * <p>Estimate: each run of letters/digits costs ceil(len / 3) tokens (identifiers, numbers and
 * rare words fragment under BPE), each other non-whitespace character costs 1, whitespace is free.
 * The estimate is multiplied by {@code safetyFactor}. The constants are placeholders to be
 * calibrated per target model against its real tokenizer; they are not research findings.
 */
public final class HeuristicTokenCounter implements TokenCounter {
    public static final String ID = "heuristic-upper-bound-v1";

    private final double safetyFactor;

    public HeuristicTokenCounter() {
        this(1.15);
    }

    public HeuristicTokenCounter(double safetyFactor) {
        if (safetyFactor < 1.0) {
            throw new IllegalArgumentException("safetyFactor must be >= 1.0");
        }
        this.safetyFactor = safetyFactor;
    }

    @Override
    public TokenCount count(String text) {
        int raw = 0;
        int run = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                run++;
                continue;
            }
            raw += (run + 2) / 3;
            run = 0;
            if (!Character.isWhitespace(c)) {
                raw++;
            }
        }
        raw += (run + 2) / 3;
        return new TokenCount((int) Math.ceil(raw * safetyFactor), false, ID);
    }

    @Override
    public String id() {
        return ID;
    }
}
