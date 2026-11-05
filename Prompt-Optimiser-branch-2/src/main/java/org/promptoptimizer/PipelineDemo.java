package org.promptoptimizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.promptoptimizer.agents.KaggleAgent;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.model.Model.CanonicalDocument;
import org.promptoptimizer.model.Model.InformationUnit;
import org.promptoptimizer.model.Model.UnitType;
import org.promptoptimizer.segment.ProfileSegmenter;
import org.promptoptimizer.task.RuleBasedTaskProfiler;
import org.promptoptimizer.task.TaskProfile;
import org.promptoptimizer.token.HeuristicTokenCounter;

/** Offline command-line demo of prompt profiling and segmentation. */
public final class PipelineDemo {
    private static final long MAX_DOCUMENT_BYTES = 8L * 1024 * 1024;
    private static final Pattern OUTPUT_CUE = Pattern.compile(
            "(?i)\\b(?:return|output|provide)\\b.*\\b(?:code|source|format|report|json|markdown)\\b");
    private static final String DEFAULT_PROMPT =
            "Develop a Python-based library management system that helps librarians manage books, registered users, and borrowing activities efficiently. The system should allow librarians to add new books to the collection and remove books that are no longer available. Each book must have its title, author, ISBN, publication year, and available copy count recorded. Librarians should be able to search for books using their title, author, or ISBN without manually browsing the entire collection. The system must also support user registration and store details such as name, user ID, and contact information. Registered users should be able to borrow available books, with the system recording the borrowing date and expected return date for each transaction. When a book is returned, its availability and copy count should be updated automatically. The system should identify overdue books by comparing their return dates with the current date and notify librarians. Additionally, provide a dashboard showing the total number of books, registered users, borrowed books, and overdue books. Develop the application using Python without external libraries or third-party frameworks. Use built-in functionality wherever possible and ensure that library records are stored persistently so they remain available after restarting the application. Validate user inputs to prevent incomplete or invalid records. Keep the implementation modular, readable, and easy to maintain. Return the complete Python source code, organized into logical functions with comments explaining important operations. Avoid lengthy explanations outside the code.";

    private PipelineDemo() {}

    public static void main(String[] args) throws Exception {
        Options options = parse(args);
        if (options.help()) {
            printUsage();
            return;
        }

        Canonicalizer canonicalizer = new Canonicalizer(MAX_DOCUMENT_BYTES);
        CanonicalDocument prompt = readPrompt(options.prompt(), canonicalizer);
        TaskProfile profile = new RuleBasedTaskProfiler().profile(prompt);
        List<InformationUnit> units = new ProfileSegmenter(profile)
                .segment(prompt, new HeuristicTokenCounter());
        String split = formatSplit(prompt, units, profile);
        System.out.println("Input JSON:");
        System.out.println(split);
        System.out.flush();
        String optimized = new KaggleAgent().optimize(split);
        System.out.println("\nOptimized JSON:");
        System.out.println(optimized);
    }

    static String formatSplit(CanonicalDocument prompt, List<InformationUnit> units, TaskProfile profile) {
        String task = "";
        List<String> requirements = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        List<String> outputFormats = new ArrayList<>();
        for (InformationUnit unit : units) {
            String text = unit.slice(prompt).strip();
            if (text.isEmpty()) {
                continue;
            }
            switch (labelFor(unit, profile)) {
                case "TASK" -> {
                    if (task.isEmpty()) {
                        task = text;
                    } else {
                        requirements.add(text);
                    }
                }
                case "CONSTRAINT" -> constraints.add(text);
                case "OUTPUT FORMAT" -> outputFormats.add(text);
                default -> requirements.add(text);
            }
        }

        StringBuilder json = new StringBuilder("{\n  \"TASK\": ");
        appendJsonString(json, task);
        json.append(",\n  \"REQUIREMENTS\": ");
        appendJsonArray(json, new LinkedHashSet<>(requirements));
        json.append(",\n  \"CONSTRAINTS\": ");
        appendJsonArray(json, new LinkedHashSet<>(constraints));
        json.append(",\n  \"OUTPUT FORMAT\": ");
        appendJsonString(json, String.join(" ", new LinkedHashSet<>(outputFormats)));
        return json.append("\n}").toString();
    }

    private static void appendJsonArray(StringBuilder json, Iterable<String> values) {
        json.append('[');
        boolean first = true;
        for (String value : values) {
            if (!first) {
                json.append(',');
            }
            json.append("\n    ");
            appendJsonString(json, value);
            first = false;
        }
        if (first) {
            json.append(']');
        } else {
            json.append("\n  ]");
        }
    }

    private static void appendJsonString(StringBuilder json, String value) {
        json.append('"');
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            switch (current) {
                case '"' -> json.append("\\\"");
                case '\\' -> json.append("\\\\");
                case '\b' -> json.append("\\b");
                case '\f' -> json.append("\\f");
                case '\n' -> json.append("\\n");
                case '\r' -> json.append("\\r");
                case '\t' -> json.append("\\t");
                default -> {
                    if (current < 0x20) {
                        json.append(String.format("\\u%04x", (int) current));
                    } else {
                        json.append(current);
                    }
                }
            }
        }
        json.append('"');
    }

    private static String labelFor(InformationUnit unit, TaskProfile profile) {
        if (profile.taskStatement().filter(item -> overlaps(item, unit)).isPresent()) {
            return "TASK";
        }
        if (profile.constraints().stream().anyMatch(item -> overlaps(item, unit))) {
            return "CONSTRAINT";
        }
        if (profile.outputs().stream().anyMatch(item -> overlaps(item, unit))) {
            return "OUTPUT FORMAT";
        }
        if (unit.type() == UnitType.REQUIREMENT
                && OUTPUT_CUE.matcher(unit.slice(profile.prompt()).strip()).find()) {
            return "OUTPUT FORMAT";
        }
        if (profile.requirements().stream().anyMatch(item -> overlaps(item, unit))) {
            return "REQUIREMENT";
        }
        return unit.type().name();
    }

    private static boolean overlaps(TaskProfile.ExtractedItem item, InformationUnit unit) {
        return item.source().start() < unit.end() && unit.start() < item.source().end();
    }

    private static CanonicalDocument readPrompt(Path path, Canonicalizer canonicalizer) throws IOException {
        if (path == null) {
            return canonicalizer.canonicalize("prompt", DEFAULT_PROMPT);
        }
        return canonicalizer.canonicalize("prompt", readBytes(path));
    }

    private static byte[] readBytes(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("Input file does not exist or is not a regular file: " + path);
        }
        long size = Files.size(path);
        if (size > MAX_DOCUMENT_BYTES) {
            throw new IllegalArgumentException("Input file exceeds the " + MAX_DOCUMENT_BYTES + "-byte limit: " + path);
        }
        return Files.readAllBytes(path);
    }

    private static Options parse(String[] args) {
        Path prompt = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help", "-h" -> {
                    return new Options(null, true);
                }
                case "--prompt" -> prompt = Path.of(requireValue(args, ++i, "--prompt"));
                default -> throw new IllegalArgumentException("Unknown argument: " + args[i] + ". Use --help.");
            }
        }
        return new Options(prompt, false);
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a value.");
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("""
                PromptOptimizer prompt-splitting demo
                Usage: java -cp target/classes org.promptoptimizer.PipelineDemo [options]
                  --prompt <file>   UTF-8 prompt file (optional; uses a labeled example if omitted)
                  --help            Show this help

                Profiles and prints the prompt split, sends that split to the Kaggle agent,
                and prints the optimized prompt. Configure KAGGLE_API_URL and KAGGLE_API_TOKEN
                in the project-root .env file before running.
                """);
    }

    private record Options(Path prompt, boolean help) {}
}
