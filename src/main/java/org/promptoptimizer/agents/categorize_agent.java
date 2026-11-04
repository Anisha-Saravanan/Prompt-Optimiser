package org.promptoptimizer.agents;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Properties;
import java.util.Scanner;

public class categorize_agent implements ClassificationAgent {
    private final HttpClient client;
    private final String apiUrl;
    private final String apiToken;

    public categorize_agent() throws IOException {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build(), loadEnv());
    }

    private categorize_agent(HttpClient client, Properties env) {
        this(client, getEnvValue(env, "KAGGLE_API_URL"), getEnvValue(env, "KAGGLE_API_TOKEN"));
    }

    public categorize_agent(HttpClient client, String apiUrl, String apiToken) {
        this.client = Objects.requireNonNull(client);
        this.apiUrl = Objects.requireNonNull(apiUrl).replaceAll("/+$", "");
        this.apiToken = Objects.requireNonNull(apiToken);
        if (this.apiUrl.isBlank() || this.apiToken.isBlank()) {
            throw new IllegalArgumentException("KAGGLE_API_URL and KAGGLE_API_TOKEN must not be blank");
        }
    }

    @Override
    public String classify(String normalizedPrompt, String instructionPrompt) throws IOException, InterruptedException {
        Objects.requireNonNull(normalizedPrompt);
        Objects.requireNonNull(instructionPrompt);
        String requestPrompt = instructionPrompt.isBlank()
                ? normalizedPrompt
                : instructionPrompt + "\n\nPrompt to classify:\n" + normalizedPrompt;
        String json = "{\"prompt\":\"" + escapeJson(requestPrompt) + "\",\"think\":false}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + "/generate"))
                .timeout(Duration.ofSeconds(320))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiToken)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Classification API returned HTTP status " + response.statusCode());
        }
        return response.body();
    }

    public static void main(String[] args) throws Exception {
        categorize_agent agent = new categorize_agent();
        try (Scanner sc = new Scanner(System.in)) {
            System.out.print("Enter your prompt: ");
            String prompt = sc.nextLine();
            System.out.println("LLM Response: " + agent.classify(prompt, ""));
        }
    }

    private static Properties loadEnv() throws IOException {
        Path envFile = Path.of(".env");
        if (!Files.isRegularFile(envFile)) {
            throw new IOException("Could not find .env. Run the program from the project root.");
        }

        Properties env = new Properties();
        try (var reader = Files.newBufferedReader(envFile, StandardCharsets.UTF_8)) {
            env.load(reader);
        }
        if (getEnvValue(env, "KAGGLE_API_URL") == null || getEnvValue(env, "KAGGLE_API_TOKEN") == null) {
            throw new IOException("Set KAGGLE_API_URL and KAGGLE_API_TOKEN in .env.");
        }
        return env;
    }

    private static String getEnvValue(Properties env, String key) {
        String value = env.getProperty(key);
        if (value == null) {
            return null;
        }

        value = value.trim();
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
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
}