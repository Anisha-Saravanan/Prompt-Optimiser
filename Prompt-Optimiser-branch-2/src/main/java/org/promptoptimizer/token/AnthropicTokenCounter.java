package org.promptoptimizer.token;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.promptoptimizer.token.TokenCounter.TokenCount;

/**
 * Counts one user message with Anthropic's Messages token-count endpoint when configured.
 * The supplied fallback is always marked non-exact and is used explicitly when the API is absent or unavailable.
 */
public final class AnthropicTokenCounter implements TokenCounter {
    public static final String DEFAULT_MODEL = "claude-sonnet-5-5";
    public static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages/count_tokens";
    private static final String ANTHROPIC_VERSION = "2026-10-21";
    private static final int MAX_CACHED_TEXTS = 2_048;
    private static final Pattern INPUT_TOKENS = Pattern.compile("\"input_tokens\"\\s*:\\s*(\\d+)");
    private static final List<Pattern> SENSITIVE_PATTERNS = List.of(
            Pattern.compile("\\b(?:AKIA|ASIA)[A-Z0-9]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{30,}\\b"),
            Pattern.compile("\\bsk-(?:proj-)?[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
            Pattern.compile("\\bglpat-[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
            Pattern.compile("(?i)\\b(?:api[_-]?key|access[_-]?token|password|passwd|secret)\\b"
                    + "\\s*[:=]\\s*[\"']?[^\\s\"',;]{8,}"));
    private static final Logger LOGGER = Logger.getLogger(AnthropicTokenCounter.class.getName());

    private final String apiKey;
    private final String model;
    private final String id;
    private final URI endpoint;
    private final HttpClient client;
    private final TokenCounter fallback;
    private final ConcurrentHashMap<String, Integer> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean fallbackWarningLogged = new AtomicBoolean();

    public AnthropicTokenCounter(String apiKey, String model, TokenCounter fallback) {
        this(apiKey, model, fallback, URI.create(DEFAULT_ENDPOINT),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    AnthropicTokenCounter(String apiKey, String model, TokenCounter fallback, URI endpoint, HttpClient client) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        this.model = model.trim();
        this.fallback = Objects.requireNonNull(fallback);
        this.endpoint = Objects.requireNonNull(endpoint);
        this.client = Objects.requireNonNull(client);
        this.id = "anthropic-count-tokens-v" + ANTHROPIC_VERSION + ":" + this.model + "+fallback:" + fallback.id();
    }

    public static AnthropicTokenCounter configured(TokenCounter fallback) {
        return new AnthropicTokenCounter(System.getenv("ANTHROPIC_API_KEY"), DEFAULT_MODEL, fallback);
    }

    @Override
    public TokenCount count(String text) {
        Objects.requireNonNull(text);
        if (apiKey.isBlank()) {
            return fallbackCount(text);
        }
        if (containsSensitiveMaterial(text)) {
            return fallbackCount(text, new IOException(
                    "possible secret detected; external token counting was skipped"));
        }
        String hash = sha256(text);
        Integer cached = cache.get(hash);
        if (cached != null) {
            return new TokenCount(cached, true, id);
        }

        try {
            int tokens = requestCount(text);
            if (cache.size() >= MAX_CACHED_TEXTS) {
                cache.clear();
            }
            cache.put(hash, tokens);
            return new TokenCount(tokens, true, id);
        } catch (IOException e) {
            return fallbackCount(text, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallbackCount(text, e);
        }
    }

    @Override
    public String id() {
        return id;
    }

    private int requestCount(String text) throws IOException, InterruptedException {
        String body = "{\"model\":\"" + escapeJson(model)
                + "\",\"messages\":[{\"role\":\"user\",\"content\":\"" + escapeJson(text) + "\"}]}";
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(30))
                .header("content-type", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("x-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Anthropic token-count endpoint returned HTTP " + response.statusCode());
        }
        Matcher matcher = INPUT_TOKENS.matcher(response.body());
        if (!matcher.find()) {
            throw new IOException("Anthropic token-count response omitted input_tokens");
        }
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (NumberFormatException e) {
            throw new IOException("Anthropic token-count response contained an invalid input_tokens value", e);
        }
    }

    private TokenCount fallbackCount(String text, IOException failure) {
        if (failure != null && fallbackWarningLogged.compareAndSet(false, true)) {
            LOGGER.warning("Anthropic token counting unavailable; using non-exact fallback "
                    + fallback.id() + " (" + failure.getMessage() + ").");
        }
        TokenCount estimate = fallback.count(text);
        return new TokenCount(estimate.tokens(), false, id);
    }

    private TokenCount fallbackCount(String text) {
        TokenCount estimate = fallback.count(text);
        return new TokenCount(estimate.tokens(), false, id);
    }

    private TokenCount fallbackCount(String text, InterruptedException failure) {
        if (fallbackWarningLogged.compareAndSet(false, true)) {
            LOGGER.warning("Anthropic token counting interrupted; using non-exact fallback " + fallback.id() + ".");
        }
        TokenCount estimate = fallback.count(text);
        return new TokenCount(estimate.tokens(), false, id);
    }

    private static String escapeJson(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static boolean containsSensitiveMaterial(String text) {
        return SENSITIVE_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }
}
