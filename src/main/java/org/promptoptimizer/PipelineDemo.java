package org.promptoptimizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import org.promptoptimizer.agents.KaggleAgent;
import org.promptoptimizer.agents.PromptChecker;
import org.promptoptimizer.agents.PromptChecker.CheckResult;
import org.promptoptimizer.agents.SegmentAgent;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.model.Model.CanonicalDocument;

/** Offline command-line demo of prompt profiling and segmentation. */
public final class PipelineDemo {
    private static final long MAX_DOCUMENT_BYTES = 8L * 1024 * 1024;
    private static final int FEEDBACK_ITERATIONS = 5;
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

    /** Runs the segmentation-agent, optimization, and feedback workflow for one prompt. */
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
        String split = new SegmentAgent().segment(prompt.text());
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

                Sends the prompt to the segmentation agent to categorize it as TASK,
                REQUIREMENTS, and CONSTRAINTS, then optimizes the result and runs the
                preservation-check workflow. Configure KAGGLE_API_URL and KAGGLE_API_TOKEN
                in the project-root .env file before running.
                """);
    }

    private record Options(Path prompt, boolean help) {}
}
