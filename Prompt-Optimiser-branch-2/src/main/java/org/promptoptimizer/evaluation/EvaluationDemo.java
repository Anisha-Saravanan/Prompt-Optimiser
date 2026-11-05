package org.promptoptimizer.evaluation;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.promptoptimizer.ingest.Canonicalizer;
import org.promptoptimizer.lossless.LosslessResult.ContentFormat;
import org.promptoptimizer.lossless.LosslessResult.DocRole;
import org.promptoptimizer.lossless.LosslessResult.PreparedDocument;
import org.promptoptimizer.model.Model.CanonicalDocument;
import org.promptoptimizer.model.Model.InformationUnit;
import org.promptoptimizer.protect.RuleBasedProtectedSpanDetector;
import org.promptoptimizer.recover.RecoveryController;
import org.promptoptimizer.recover.RecoveryOutcome;
import org.promptoptimizer.segment.ParagraphSegmenter;
import org.promptoptimizer.segment.ProfileSegmenter;
import org.promptoptimizer.task.RuleBasedTaskProfiler;
import org.promptoptimizer.task.TaskProfile;
import org.promptoptimizer.token.HeuristicTokenCounter;
import org.promptoptimizer.token.TokenCounter;

/** Reproducible, offline runner for the seeded optimizer-only evaluation. */
public final class EvaluationDemo {
    private static final long MAX_DOCUMENT_BYTES = 8L * 1024 * 1024;

    private EvaluationDemo() {}

    public static void main(String[] args) throws Exception {
        Options options = parse(args);
        if (options.help()) {
            printUsage();
            return;
        }
        TokenCounter counter = new HeuristicTokenCounter();
        List<EvaluationSupport.ExperimentRow> rows = EvaluationSupport.runOffline(
                new EvaluationSupport.Config(EvaluationSupport.DATASET_VERSION, options.seed(),
                        options.repeats(), List.of(), false, false),
                EvaluationSupport.seededJavaCases(),
                Map.of("pipeline", item -> optimize(item, counter, options.budget())), counter);
        EvaluationSupport.exportCsv(options.output(), rows);
        System.out.printf("Wrote %d per-case rows and macro/micro summaries to %s; tokenizer=%s (exact=false).%n",
                rows.size(), options.output(), counter.id());
        System.out.println("Task-performance scores are intentionally empty; repeats do not measure stochasticity.");
    }

    private static String optimize(EvaluationSupport.SeedCase item, TokenCounter counter, Integer budget) {
        Canonicalizer canonicalizer = new Canonicalizer(MAX_DOCUMENT_BYTES);
        RuleBasedProtectedSpanDetector detector = new RuleBasedProtectedSpanDetector();
        CanonicalDocument prompt = canonicalizer.canonicalize("prompt", item.taskPrompt());
        CanonicalDocument source = canonicalizer.canonicalize("Seed.java", item.javaSource());
        TaskProfile profile = new RuleBasedTaskProfiler().profile(prompt);
        List<InformationUnit> promptUnits = new ProfileSegmenter(profile).segment(prompt, counter);
        List<InformationUnit> sourceUnits = new ParagraphSegmenter().segment(source, counter);
        List<PreparedDocument> documents = new ArrayList<>();
        documents.add(new PreparedDocument(prompt, DocRole.PROMPT, ContentFormat.PLAIN_TEXT, promptUnits,
                detector.detect(prompt)));
        documents.add(new PreparedDocument(source, DocRole.FILE, ContentFormat.SOURCE_CODE, sourceUnits,
                detector.detect(source)));
        RecoveryOutcome result = RecoveryController.standard(counter, detector).run(documents, profile, budget);
        if (!result.deliverable()) {
            throw new IllegalStateException("Seed case " + item.id() + " is infeasible at the requested budget: "
                    + result.infeasibility().minimumTokens() + " minimum tokens for budget " + budget);
        }
        return result.text();
    }

    private static Options parse(String[] args) {
        Path output = Path.of("target", "evaluation", EvaluationSupport.DATASET_VERSION + ".csv");
        Integer budget = null;
        long seed = 17;
        int repeats = 1;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--help", "-h" -> {
                    return new Options(output, null, seed, repeats, true);
                }
                case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                case "--budget" -> budget = parseNonNegativeInt(requireValue(args, ++i, "--budget"), "--budget");
                case "--seed" -> {
                    try {
                        seed = Long.parseLong(requireValue(args, ++i, "--seed"));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--seed must be a non-negative integer", e);
                    }
                    if (seed < 0) {
                        throw new IllegalArgumentException("--seed must be a non-negative integer");
                    }
                }
                case "--repeats" -> repeats = parseNonNegativeInt(
                        requireValue(args, ++i, "--repeats"), "--repeats");
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        if (repeats == 0) {
            throw new IllegalArgumentException("--repeats must be at least 1");
        }
        return new Options(output, budget, seed, repeats, false);
    }

    private static int parseNonNegativeInt(String value, String option) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0) {
                throw new IllegalArgumentException(option + " must be a non-negative integer");
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(option + " must be a non-negative integer", e);
        }
    }

    private static String requireValue(String[] args, int index, String option) {
        if (index >= args.length || args[index].startsWith("--")) {
            throw new IllegalArgumentException(option + " requires a value");
        }
        return args[index];
    }

    private static void printUsage() {
        System.out.println("""
                Usage: java -cp target/classes org.promptoptimizer.evaluation.EvaluationDemo [options]
                  --output <csv>   Result destination (default target/evaluation/seeded-java-debug-v1.csv)
                  --budget <n>     Optional input token budget for each seeded case
                  --seed <n>       Reproducible experiment seed (default 17)
                  --repeats <n>    Optimizer runs per case and arm (default 1)

                Runs the local optimizer with heuristic token estimates. It makes no model calls and
                does not score downstream answer quality.
                """);
    }

    private record Options(Path output, Integer budget, long seed, int repeats, boolean help) {}
}
