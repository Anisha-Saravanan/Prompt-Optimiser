package org.promptoptimizer.token;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.promptoptimizer.token.TokenCounter.TokenCount;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicTokenCounterTest {
    @Test
    void countsWithTheConfiguredClaudeModelAndCachesWithoutRetainingInputText() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            requests.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"input_tokens\":37}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        try {
            AnthropicTokenCounter counter = counter(server, "secret");

            TokenCount first = counter.count("Please debug this.");
            TokenCount second = counter.count("Please debug this.");

            assertEquals(37, first.tokens());
            assertTrue(first.exact());
            assertEquals(counter.id(), first.tokenizerId());
            assertEquals(first, second);
            assertEquals(1, requests.get());
            assertTrue(requestBody.get().contains("\"model\":\"claude-sonnet-5-5\""));
            assertTrue(requestBody.get().contains("\"content\":\"Please debug this.\""));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingCredentialUsesAnExplicitlyNonExactLocalEstimate() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> requests.incrementAndGet());
        try {
            TokenCounter fallback = new HeuristicTokenCounter();
            AnthropicTokenCounter counter = counter(server, "");

            TokenCount result = counter.count("Local only.");

            assertEquals(fallback.count("Local only.").tokens(), result.tokens());
            assertFalse(result.exact());
            assertEquals(counter.id(), result.tokenizerId());
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void providerFailureFallsBackWithoutClaimingExactness() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        try {
            AnthropicTokenCounter counter = counter(server, "secret");

            TokenCount result = counter.count("Fallback after provider failure.");

            assertFalse(result.exact());
            assertEquals(counter.id(), result.tokenizerId());
            assertEquals(new HeuristicTokenCounter().count("Fallback after provider failure.").tokens(),
                    result.tokens());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void likelySecretsAreNeverSentToTheProvider() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = server(exchange -> requests.incrementAndGet());
        try {
            AnthropicTokenCounter counter = counter(server, "secret");

            TokenCount result = counter.count("api_key=abcdefghijklmno");

            assertFalse(result.exact());
            assertEquals(counter.id(), result.tokenizerId());
            assertEquals(0, requests.get());
        } finally {
            server.stop(0);
        }
    }

    private static AnthropicTokenCounter counter(HttpServer server, String apiKey) {
        return new AnthropicTokenCounter(apiKey, AnthropicTokenCounter.DEFAULT_MODEL,
                new HeuristicTokenCounter(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages/count_tokens"),
                java.net.http.HttpClient.newHttpClient());
    }

    private static HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages/count_tokens", handler);
        server.start();
        return server;
    }
}
