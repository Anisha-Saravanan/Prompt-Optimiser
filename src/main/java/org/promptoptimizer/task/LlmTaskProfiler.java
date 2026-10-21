package org.promptoptimizer.task;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.promptoptimizer.agents.ClassificationAgent;
import org.promptoptimizer.model.CanonicalDocument;
import org.promptoptimizer.task.ProfileWarning.Code;
import org.promptoptimizer.task.ProfileWarning.Severity;
import org.promptoptimizer.task.Provenance.Basis;

/**
 * Adapts the classification agent's structured response to the existing TaskProfile contract.
 * The expected response is {@code {"items":[{"kind":"TASK","text":"exact source text"}]}};
 * {@code kind} must be TASK, REQUIREMENT, CONSTRAINT, or OUTPUT_EXPECTATION. Items must appear in
 * source order, and each text value must be an exact, nonblank slice of the normalized prompt.
 */
public final class LlmTaskProfiler implements TaskProfiler {
    public static final String VERSION = "llm-1";

    /** Fill this with instructions for the API model to return the documented JSON items schema. */
    public static final String CLASSIFICATION_PROMPT = "";

    private final ClassificationAgent agent;

    public LlmTaskProfiler(ClassificationAgent agent) {
        this.agent = Objects.requireNonNull(agent);
    }

    @Override
    public TaskProfile profile(CanonicalDocument prompt) {
        Objects.requireNonNull(prompt);
        if (prompt.text().isEmpty()) {
            return new TaskProfile(prompt, null, List.of(), List.of(), List.of(), List.of(),
                    List.of(new ProfileWarning(Code.EMPTY_INPUT, Severity.INFO, "Prompt is empty.", null)),
                    Confidence.LOW, VERSION);
        }

        String response;
        try {
            response = agent.classify(prompt.text(), CLASSIFICATION_PROMPT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TaskProfilingException("LLM classification was interrupted.", e);
        } catch (IOException e) {
            throw new TaskProfilingException("LLM classification request failed.", e);
        }

        List<ResponseItem> responseItems = parseResponse(response);
        EnumMap<ItemKind, List<ExtractedItem>> items = new EnumMap<>(ItemKind.class);
        for (ItemKind kind : ItemKind.values()) {
            items.put(kind, new ArrayList<>());
        }
        List<ProfileWarning> warnings = new ArrayList<>();
        ExtractedItem task = null;
        int searchFrom = 0;
        for (ResponseItem responseItem : responseItems) {
            int start = prompt.text().indexOf(responseItem.text(), searchFrom);
            if (start < 0) {
                throw new TaskProfilingException("Classified text is not an exact, ordered slice of the prompt.");
            }
            int end = start + responseItem.text().length();
            SourceRef source = new SourceRef(prompt.id(), start, end);
            String idPrefix = prefix(responseItem.kind());
            List<ExtractedItem> kindItems = items.get(responseItem.kind());
            if (responseItem.kind() == ItemKind.TASK && !kindItems.isEmpty()) {
                throw new TaskProfilingException("The response contains more than one TASK item.");
            }
            ExtractedItem item = new ExtractedItem(
                    idPrefix + (kindItems.size() + 1),
                    responseItem.kind(),
                    label(responseItem.kind()),
                    responseItem.text(),
                    new Provenance(source, "llm-classification", Basis.LLM_CLASSIFICATION),
                    Confidence.MEDIUM,
                    List.of());
            kindItems.add(item);
            if (responseItem.kind() == ItemKind.TASK) {
                task = item;
            }
            searchFrom = end;
        }

        if (task == null) {
            warnings.add(new ProfileWarning(Code.MISSING_TASK, Severity.WARN,
                    "The LLM response did not classify a task statement.", null));
        }
        return new TaskProfile(prompt, task, items.get(ItemKind.REQUIREMENT), items.get(ItemKind.CONSTRAINT),
                items.get(ItemKind.OUTPUT_EXPECTATION), List.of(), warnings,
                responseItems.isEmpty() ? Confidence.LOW : Confidence.MEDIUM, VERSION);
    }

    private static String prefix(ItemKind kind) {
        return switch (kind) {
            case TASK -> "T";
            case REQUIREMENT -> "R";
            case CONSTRAINT -> "C";
            case OUTPUT_EXPECTATION -> "O";
        };
    }

    private static String label(ItemKind kind) {
        return switch (kind) {
            case TASK -> "task";
            case REQUIREMENT -> "requirement";
            case CONSTRAINT -> "restriction";
            case OUTPUT_EXPECTATION -> "format";
        };
    }

    private static List<ResponseItem> parseResponse(String response) {
        Object parsed;
        try {
            if (response == null) {
                throw new IllegalArgumentException("empty response");
            }
            parsed = new JsonReader(response).parse();
        } catch (IllegalArgumentException e) {
            throw new TaskProfilingException("LLM response is not valid JSON.", e);
        }
        if (!(parsed instanceof Map<?, ?> root) || !(root.get("items") instanceof List<?> rawItems)) {
            throw new TaskProfilingException("LLM response must be a JSON object with an 'items' array.");
        }
        List<ResponseItem> result = new ArrayList<>();
        for (Object raw : rawItems) {
            if (!(raw instanceof Map<?, ?> item)
                    || !(item.get("kind") instanceof String kindValue)
                    || !(item.get("text") instanceof String text)) {
                throw new TaskProfilingException("Each classified item must contain string 'kind' and 'text' fields.");
            }
            ItemKind kind;
            try {
                kind = ItemKind.valueOf(kindValue);
            } catch (IllegalArgumentException e) {
                throw new TaskProfilingException("Unsupported classification kind: " + kindValue, e);
            }
            if (text.isBlank()) {
                throw new TaskProfilingException("Classified item text must not be blank.");
            }
            result.add(new ResponseItem(kind, text));
        }
        return List.copyOf(result);
    }

    private record ResponseItem(ItemKind kind, String text) {}

    private static final class JsonReader {
        private final String input;
        private int position;

        JsonReader(String input) {
            this.input = input;
        }

        Object parse() {
            Object value = readValue();
            whitespace();
            if (position != input.length()) {
                fail("trailing content");
            }
            return value;
        }

        private Object readValue() {
            whitespace();
            if (position >= input.length()) {
                return fail("unexpected end of input");
            }
            return switch (input.charAt(position)) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> {
                    if (input.charAt(position) == '-' || Character.isDigit(input.charAt(position))) {
                        yield readNumber();
                    }
                    yield fail("unexpected value");
                }
            };
        }

        private Map<String, Object> readObject() {
            position++;
            whitespace();
            Map<String, Object> result = new java.util.LinkedHashMap<>();
            if (consume('}')) {
                return result;
            }
            while (true) {
                whitespace();
                if (position >= input.length() || input.charAt(position) != '"') {
                    return fail("object key must be a string");
                }
                String key = readString();
                whitespace();
                require(':');
                Object value = readValue();
                if (result.containsKey(key)) {
                    return fail("duplicate object key");
                }
                result.put(key, value);
                whitespace();
                if (consume('}')) {
                    return result;
                }
                require(',');
            }
        }

        private List<Object> readArray() {
            position++;
            whitespace();
            List<Object> result = new ArrayList<>();
            if (consume(']')) {
                return result;
            }
            while (true) {
                result.add(readValue());
                whitespace();
                if (consume(']')) {
                    return result;
                }
                require(',');
            }
        }

        private String readString() {
            require('"');
            StringBuilder result = new StringBuilder();
            while (position < input.length()) {
                char c = input.charAt(position++);
                if (c == '"') {
                    return result.toString();
                }
                if (c < 0x20) {
                    return fail("unescaped control character");
                }
                if (c != '\\') {
                    result.append(c);
                    continue;
                }
                if (position >= input.length()) {
                    return fail("incomplete escape");
                }
                char escape = input.charAt(position++);
                switch (escape) {
                    case '"', '\\', '/' -> result.append(escape);
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> result.append(readUnicode());
                    default -> { return fail("invalid escape"); }
                }
            }
            return fail("unterminated string");
        }

        private char readUnicode() {
            if (position + 4 > input.length()) {
                return fail("incomplete unicode escape");
            }
            try {
                char value = (char) Integer.parseInt(input.substring(position, position + 4), 16);
                position += 4;
                return value;
            } catch (NumberFormatException e) {
                return fail("invalid unicode escape");
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!input.startsWith(literal, position)) {
                return fail("invalid literal");
            }
            position += literal.length();
            return value;
        }

        private Number readNumber() {
            int start = position;
            consume('-');
            if (!consume('0')) {
                digits();
            }
            if (consume('.')) {
                digits();
            }
            if (consume('e') || consume('E')) {
                if (!consume('+')) {
                    consume('-');
                }
                digits();
            }
            try {
                return new java.math.BigDecimal(input.substring(start, position));
            } catch (NumberFormatException e) {
                return fail("invalid number");
            }
        }

        private void digits() {
            int start = position;
            while (position < input.length() && input.charAt(position) >= '0' && input.charAt(position) <= '9') {
                position++;
            }
            if (position == start) {
                fail("expected digit");
            }
        }

        private void whitespace() {
            while (position < input.length() && " \t\r\n".indexOf(input.charAt(position)) >= 0) {
                position++;
            }
        }

        private boolean consume(char expected) {
            if (position < input.length() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void require(char expected) {
            if (!consume(expected)) {
                fail("expected '" + expected + "'");
            }
        }

        private <T> T fail(String message) {
            throw new IllegalArgumentException(message + " at character " + position);
        }
    }
}
