package org.promptoptimizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.promptoptimizer.agents.KaggleAgent;
import org.promptoptimizer.agents.PromptChecker;
import org.promptoptimizer.agents.PromptChecker.CheckResult;
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
    private static final int FEEDBACK_ITERATIONS = 5;
    private static final Pattern OUTPUT_CUE = Pattern.compile(
            "(?i)\\b(?:return|output|provide)\\b.*\\b(?:code|source|format|report|json|markdown)\\b");
    private static final String DEFAULT_PROMPT =
            "Develop a Python-based student attendance management system for a college.\n" +
                    "\n" +
                    "The system must allow administrators to register students with their name, roll number, department, year of study, and email address. Each student must have a unique roll number.\n" +
                    "\n" +
                    "Teachers should be able to create attendance sessions for a specific subject, class, and date. They must be able to mark each student as present, absent, or late. Attendance records must store the student's roll number, subject, date, status, and the teacher who marked the attendance.\n" +
                    "\n" +
                    "Students should be able to view their attendance percentage for each subject. Calculate the percentage using the number of classes attended divided by the total number of classes conducted, multiplied by 100. Late attendance should count as half an attended class.\n" +
                    "\n" +
                    "The system must automatically identify students whose attendance percentage falls below 75% in any subject and display them in a separate warning list.\n" +
                    "\n" +
                    "Administrators should be able to search students by roll number or name and generate attendance reports for individual students and entire classes. Reports must include total classes, attended classes, absences, late arrivals, and attendance percentage.\n" +
                    "\n" +
                    "Use Python only, without external libraries or frameworks. Store all student details and attendance records persistently so that data remains available after the program restarts. Validate inputs, prevent duplicate roll numbers, and prevent teachers from marking attendance twice for the same student in the same subject and session.\n" +
                    "\n" +
                    "Organize the program into modular functions with clear comments. Return the complete source code without lengthy explanations.";

    private PipelineDemo() {}

    public static void main(String[] args) throws Exception {
        Options options = parse(args);
        if (options.help()) {
            printUsage();
            return;
        }

        Canonicalizer canonicalizer = new Canonicalizer(MAX_DOCUMENT_BYTES);
        CanonicalDocument prompt = readPrompt(options.prompt(), canonicalizer);
        String finalPrompt = optimizePrompt(prompt,
                split -> {
                    System.out.println("Input JSON:");
                    System.out.println(split);
                    System.out.flush();
                },
                (iteration, result) -> {
                    System.out.printf("%nPrompt checker turn %d/%d%n", iteration, FEEDBACK_ITERATIONS);
                    System.out.println("Raw agent output:");
                    System.out.println(result.rawAgentOutput());
                    if (result.noCorrectionsNeeded()) {
                        System.out.println("No additions or corrections needed; stopping feedback loop.");
                    } else {
                        System.out.println("Parsed reasoning summary:");
                        System.out.println(result.reasoning());
                        System.out.println("Corrected prompt:");
                        System.out.println(result.correctedPrompt());
                    }
                    System.out.flush();
                });
        System.out.println("\nFinal optimized prompt:");
        System.out.println(finalPrompt);
    }

    /** Runs the existing profiling, optimization, and feedback workflow for one prompt. */
    public static String optimizePrompt(String originalPrompt) throws IOException, InterruptedException {
        if (originalPrompt == null || originalPrompt.isBlank()) {
            throw new IllegalArgumentException("Prompt must not be blank.");
        }
        CanonicalDocument prompt = new Canonicalizer(MAX_DOCUMENT_BYTES).canonicalize("prompt", originalPrompt);
        return optimizePrompt(prompt, ignored -> {}, (iteration, result) -> {});
    }

    private static String optimizePrompt(CanonicalDocument prompt, Consumer<String> onSplit,
                                         BiConsumer<Integer, CheckResult> onReply)
            throws IOException, InterruptedException {
        TaskProfile profile = new RuleBasedTaskProfiler().profile(prompt);
        List<InformationUnit> units = new ProfileSegmenter(profile)
                .segment(prompt, new HeuristicTokenCounter());
        String split = formatSplit(prompt, units, profile);
        onSplit.accept(split);
        String optimized = new KaggleAgent().optimize(split);
        return runFeedbackLoop(split, optimized, new PromptChecker(), onReply);
    }

    static String runFeedbackLoop(String originalPrompt, String initialOptimizedPrompt, PromptChecker checker,
                                  BiConsumer<Integer, CheckResult> onReply)
            throws IOException, InterruptedException {
        String previousPrompt = initialOptimizedPrompt;
        for (int iteration = 1; iteration <= FEEDBACK_ITERATIONS; iteration++) {
            CheckResult result = checker.check(originalPrompt, previousPrompt);
            if (result.noCorrectionsNeeded()) {
                onReply.accept(iteration, result);
                break;
            }
            previousPrompt = result.correctedPrompt();
            onReply.accept(iteration, result);
        }
        return previousPrompt;
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
