package org.promptoptimizer.evaluation;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.promptoptimizer.token.TokenCounter;
import org.promptoptimizer.token.TokenCounter.TokenCount;

/** Offline-first evaluation models, metrics, seeded cases, and opt-in provider interfaces. */
public final class EvaluationSupport {
    public static final String DATASET_VERSION = "seeded-java-debug-v1";

    private EvaluationSupport() {}

    /**
     * Token, requirement, constraint, and task-performance metrics for one item/arm.
     * A zero denominator is represented explicitly as an empty OptionalDouble.
     */
    public record Metrics(double tokenReductionPercent, double remainingTokenRatio, OptionalDouble compressionRatio,
                          Ratio requirement, Ratio constraint, OptionalDouble performanceRetentionPercent,
                          OptionalDouble oracleNormalizedScore, OptionalDouble optimizationEfficiency) {
        public Metrics {
            Objects.requireNonNull(compressionRatio);
            Objects.requireNonNull(requirement);
            Objects.requireNonNull(constraint);
            Objects.requireNonNull(performanceRetentionPercent);
            Objects.requireNonNull(oracleNormalizedScore);
            Objects.requireNonNull(optimizationEfficiency);
        }

        public static Metrics calculate(Input input) {
            Objects.requireNonNull(input);
            double reduction = input.originalTokens() == 0 ? 0
                    : 100.0 * (input.originalTokens() - input.optimizedTokens()) / input.originalTokens();
            double remaining = input.originalTokens() == 0 ? 1
                    : (double) input.optimizedTokens() / input.originalTokens();
            OptionalDouble compression = input.optimizedTokens() == 0
                    ? OptionalDouble.empty()
                    : OptionalDouble.of((double) input.originalTokens() / input.optimizedTokens());
            OptionalDouble retention = ratioPercent(input.optimizedPerformance(), input.originalPerformance());
            OptionalDouble oracle = oracleNormalized(input.optimizedPerformance(), input.closedBookPerformance(),
                    input.oraclePerformance(), input.noiseFloor());
            OptionalDouble efficiency = retention.isPresent() && remaining > 0
                    ? OptionalDouble.of(retention.getAsDouble() / remaining)
                    : OptionalDouble.empty();
            return new Metrics(reduction, remaining, compression,
                    ratio(input.requirementMatches(), input.originalRequirements(), input.optimizedRequirements(), 1),
                    ratio(input.constraintMatches(), input.originalConstraints(), input.optimizedConstraints(), 2),
                    retention, oracle, efficiency);
        }

        private static Ratio ratio(int matched, int original, int optimized, double beta) {
            OptionalDouble recall = original == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of((double) matched / original);
            OptionalDouble precision = optimized == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of((double) matched / optimized);
            OptionalDouble fBeta = OptionalDouble.empty();
            if (recall.isPresent() && precision.isPresent()) {
                double p = precision.getAsDouble();
                double r = recall.getAsDouble();
                double betaSquared = beta * beta;
                double denominator = betaSquared * p + r;
                if (denominator > 0) {
                    fBeta = OptionalDouble.of((1 + betaSquared) * p * r / denominator);
                }
            }
            return new Ratio(precision, recall, fBeta, beta);
        }

        private static OptionalDouble ratioPercent(Double numerator, Double denominator) {
            if (numerator == null || denominator == null || denominator == 0) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of(100.0 * numerator / denominator);
        }

        private static OptionalDouble oracleNormalized(Double optimized, Double closed, Double oracle,
                                                       double noiseFloor) {
            if (optimized == null || closed == null || oracle == null
                    || Math.abs(oracle - closed) <= noiseFloor) {
                return OptionalDouble.empty();
            }
            return OptionalDouble.of((optimized - closed) / (oracle - closed));
        }
    }

    public record Ratio(OptionalDouble precision, OptionalDouble recall, OptionalDouble fBeta, double beta) {
        public Ratio {
            Objects.requireNonNull(precision);
            Objects.requireNonNull(recall);
            Objects.requireNonNull(fBeta);
            if (!Double.isFinite(beta) || beta <= 0) {
                throw new IllegalArgumentException("beta must be positive and finite");
            }
        }
    }

    public record Input(int originalTokens, int optimizedTokens, int originalRequirements, int optimizedRequirements,
                        int requirementMatches, int originalConstraints, int optimizedConstraints,
                        int constraintMatches, Double originalPerformance, Double optimizedPerformance,
                        Double closedBookPerformance, Double oraclePerformance, double noiseFloor) {
        public Input {
            if (originalTokens < 0 || optimizedTokens < 0 || originalRequirements < 0 || optimizedRequirements < 0
                    || requirementMatches < 0 || originalConstraints < 0 || optimizedConstraints < 0
                    || constraintMatches < 0 || requirementMatches > originalRequirements
                    || requirementMatches > optimizedRequirements || constraintMatches > originalConstraints
                    || constraintMatches > optimizedConstraints) {
                throw new IllegalArgumentException("metric counts must be non-negative and matched counts cannot exceed totals");
            }
            checkScore(originalPerformance);
            checkScore(optimizedPerformance);
            checkScore(closedBookPerformance);
            checkScore(oraclePerformance);
            if (!Double.isFinite(noiseFloor) || noiseFloor < 0) {
                throw new IllegalArgumentException("noiseFloor must be non-negative and finite");
            }
        }

        private static void checkScore(Double score) {
            if (score != null && (!Double.isFinite(score) || score < 0 || score > 1)) {
                throw new IllegalArgumentException("performance scores must be in [0,1]");
            }
        }
    }

    /**
     * A deterministic fixture with source-keyed annotations. Annotation provenance describes fixture intent;
     * it does not imply independent human review.
     */
    public record SeedCase(String id, String category, String taskPrompt, String javaSource, String oracleHint,
                          String plantedConstraint, String expectedEvidence, Split split, String familyId,
                          Map<String, String> sources, List<GoldAnnotation> annotations) {
        public SeedCase {
            Objects.requireNonNull(id);
            Objects.requireNonNull(category);
            Objects.requireNonNull(taskPrompt);
            Objects.requireNonNull(javaSource);
            Objects.requireNonNull(oracleHint);
            Objects.requireNonNull(plantedConstraint);
            Objects.requireNonNull(expectedEvidence);
            Objects.requireNonNull(split);
            Objects.requireNonNull(familyId);
            Objects.requireNonNull(sources);
            Objects.requireNonNull(annotations);
            sources = Map.copyOf(sources);
            annotations = List.copyOf(annotations);
            if (id.isBlank() || taskPrompt.isBlank() || javaSource.isBlank() || plantedConstraint.isBlank()) {
                throw new IllegalArgumentException("seed id, task, Java source, and planted constraint are required");
            }
        }

        public String originalInput() {
            return taskPrompt + "\n\n=== FILE: Seed.java ===\n" + javaSource;
        }

        public String oracleInput() {
            return originalInput() + "\n\nORACLE HINT (evaluation only): " + oracleHint;
        }

        public String closedBookInput() {
            return taskPrompt;
        }
    }

    /** One source-grounded label. Required labels are positive; optional labels are explicit negatives. */
    public record GoldAnnotation(String id, String sourceId, int start, int end, String text, AnnotationType type,
                                 boolean required, String provenance, boolean humanReviewed) {
        public GoldAnnotation {
            Objects.requireNonNull(id);
            Objects.requireNonNull(sourceId);
            Objects.requireNonNull(text);
            Objects.requireNonNull(type);
            Objects.requireNonNull(provenance);
        }
    }

    public enum AnnotationType {
        INFORMATION_UNIT,
        REQUIREMENT,
        CONSTRAINT,
        EXACT_VALUE,
        ENTITY,
        PROTECTED_SPAN
    }

    public record DatasetIssue(String caseId, String code, String message) {
        public DatasetIssue {
            Objects.requireNonNull(caseId);
            Objects.requireNonNull(code);
            Objects.requireNonNull(message);
        }
    }

    public record ConfusionCounts(int truePositives, int falsePositives, int falseNegatives) {
        public ConfusionCounts {
            if (truePositives < 0 || falsePositives < 0 || falseNegatives < 0) {
                throw new IllegalArgumentException("confusion counts must be non-negative");
            }
        }

        private ConfusionCounts plus(ConfusionCounts other) {
            return new ConfusionCounts(truePositives + other.truePositives,
                    falsePositives + other.falsePositives, falseNegatives + other.falseNegatives);
        }
    }

    public record Scores(OptionalDouble precision, OptionalDouble recall, OptionalDouble f1) {
        public Scores {
            Objects.requireNonNull(precision);
            Objects.requireNonNull(recall);
            Objects.requireNonNull(f1);
        }
    }

    public record PreservationMetrics(ConfusionCounts counts, Scores scores) {
        public PreservationMetrics {
            Objects.requireNonNull(counts);
            Objects.requireNonNull(scores);
        }

        private static PreservationMetrics fromCounts(ConfusionCounts counts) {
            int tp = counts.truePositives();
            int fp = counts.falsePositives();
            int fn = counts.falseNegatives();
            OptionalDouble precision = tp + fp == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of((double) tp / (tp + fp));
            OptionalDouble recall = tp + fn == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of((double) tp / (tp + fn));
            int f1Denominator = 2 * tp + fp + fn;
            OptionalDouble f1 = f1Denominator == 0 ? OptionalDouble.empty()
                    : OptionalDouble.of((double) (2 * tp) / f1Denominator);
            return new PreservationMetrics(counts, new Scores(precision, recall, f1));
        }
    }

    public enum SummaryScope {
        MACRO,
        MICRO
    }

    public record AggregateRow(String datasetVersion, String arm, int repeat, SummaryScope scope,
                               Map<AnnotationType, PreservationMetrics> metrics) {
        public AggregateRow {
            Objects.requireNonNull(datasetVersion);
            Objects.requireNonNull(arm);
            Objects.requireNonNull(scope);
            metrics = Map.copyOf(metrics);
        }
    }

    public enum Split {
        TRAIN, DEV, TEST
    }

    /** Immutable synthetic fixtures. Annotations are fixture-authored and explicitly not human-reviewed. */
    public static List<SeedCase> seededJavaCases() {
        List<SeedCase> cases = List.of(
                seed("java-001", "numeric", "Debug average calculation.", "double average(int sum, int count) { return sum / count; }",
                        "Cast before division to avoid integer truncation.", "Keep the public method signature unchanged.",
                        "return (double) sum / count;", Split.TRAIN),
                seed("java-002", "boundary", "Fix the inclusive score threshold.", "boolean passed(int score) { return score > 50; }",
                        "The threshold value itself must pass.", "Do not change the passing threshold from 50.",
                        "score >= 50", Split.TRAIN, "50"),
                seed("java-003", "empty-input", "Fix the empty list handling.", "int first(List<Integer> xs) { return xs.get(0); }",
                        "Handle an empty list before accessing element zero.", "Do not add external dependencies.",
                        "xs.isEmpty()", Split.TRAIN),
                seed("java-004", "null-safety", "Fix null name handling.", "String display(String name) { return name.trim(); }",
                        "Check null before trimming.", "Preserve the display method name.",
                        "name == null", Split.TRAIN),
                seed("java-005", "overflow", "Fix the factorial overflow behavior.", "long factorial(int n) { long x = 1; for (int i=2;i<=n;i++) x *= i; return x; }",
                        "Detect overflow rather than silently returning a wrapped result.", "Keep the return type long.",
                        "Math.multiplyExact", Split.TRAIN),
                seed("java-006", "loop-boundary", "Fix the loop that skips the last element.", "for (int i = 0; i < values.length - 1; i++) total += values[i];",
                        "Include the last valid array element.", "Do not change the array parameter type.",
                        "i < values.length", Split.TRAIN),
                seed("java-007", "comparison", "Fix descending integer sorting.", "values.sort((a, b) -> a - b);",
                        "Use a comparator that cannot overflow on subtraction.", "Preserve descending order.",
                        "Integer.compare(b, a)", Split.TRAIN),
                seed("java-008", "state", "Fix the counter reset bug.", "int countPositive(int[] xs) { int count = 0; for (int x : xs) { count = 0; if (x > 0) count++; } return count; }",
                        "Initialize the counter once before the loop.", "Count only values strictly greater than zero.",
                        "int count = 0; for", Split.TRAIN),
                seed("java-009", "duplicate", "Fix duplicate counting.", "int countMatches(List<String> xs, String target) { int n=0; for (String x: xs) if (x == target) n++; return n; }",
                        "Compare string values, not object identity.", "Do not alter the list contents.",
                        "target.equals", Split.TRAIN),
                seed("java-010", "rounding", "Fix percentage calculation.", "int percent(int part, int total) { return part / total * 100; }",
                        "Perform floating point division before converting to an integer percentage.", "Return a value from 0 to 100 for valid inputs.",
                        "(double) part / total", Split.TRAIN),
                seed("java-011", "mutation", "Fix the method that mutates its input.", "List<Integer> sorted(List<Integer> xs) { xs.sort(null); return xs; }",
                        "Return a sorted copy and leave the caller's list unchanged.", "Do not mutate the input list.",
                        "new ArrayList<>(xs)", Split.TRAIN),
                seed("java-012", "sentinel", "Fix the missing-item result.", "int find(int[] xs, int target) { for (int i=0;i<xs.length;i++) if (xs[i] == target) return i; return 0; }",
                        "Return -1 when no element matches.", "Keep the result zero-based.",
                        "return -1;", Split.DEV),
                seed("java-013", "validation", "Fix negative input handling.", "int squareRootFloor(int n) { return (int) Math.sqrt(n); }",
                        "Reject negative input explicitly.", "Do not silently clamp negative values.",
                        "n < 0", Split.DEV),
                seed("java-014", "aggregation", "Fix the minimum value calculation.", "int minimum(int[] xs) { int min = 0; for (int x : xs) min = Math.min(min, x); return min; }",
                        "Initialize from the first value and handle empty input.", "Do not assume array values are positive.",
                        "xs[0]", Split.TEST),
                seed("java-015", "ordering", "Fix stable ordering by timestamp.", "events.sort((a, b) -> (int) (a.timestamp() - b.timestamp()));",
                        "Avoid narrowing timestamp differences and preserve stable chronological order.", "Keep events in chronological order.",
                        "Comparator.comparingLong", Split.TEST));
        if (cases.size() < 15 || cases.size() > 25) {
            throw new IllegalStateException("initial evaluation dataset must contain 15..25 items");
        }
        return cases;
    }

    private static SeedCase seed(String id, String category, String task, String source, String oracle,
                                 String constraint, String evidence, Split split, String... exactValues) {
        String prompt = "TASK: " + task + "\nCONSTRAINTS:\n- " + constraint
                + "\nREQUIREMENTS:\n- Explain the root cause.\n- Suggest a minimal fix.";
        String javaSource = "class Seed {\n    " + source + "\n}\n";
        Map<String, String> sources = Map.of("prompt", prompt, "Seed.java", javaSource);
        List<GoldAnnotation> annotations = new ArrayList<>();
        String provenance = "seed-fixture intent; not independently human-reviewed";
        addAnnotation(annotations, "task", "prompt", prompt, task, AnnotationType.INFORMATION_UNIT, true, provenance);
        addAnnotation(annotations, "source-unit", "Seed.java", javaSource, javaSource,
                AnnotationType.INFORMATION_UNIT, true, provenance);
        addAnnotation(annotations, "requirement-root-cause", "prompt", prompt,
                "Explain the root cause.", AnnotationType.REQUIREMENT, true, provenance);
        addAnnotation(annotations, "requirement-minimal-fix", "prompt", prompt,
                "Suggest a minimal fix.", AnnotationType.REQUIREMENT, true, provenance);
        addAnnotation(annotations, "constraint", "prompt", prompt, constraint, AnnotationType.CONSTRAINT,
                true, provenance);
        addAnnotation(annotations, "protected-constraint", "prompt", prompt, constraint,
                AnnotationType.PROTECTED_SPAN, true, provenance);
        for (int i = 0; i < exactValues.length; i++) {
            addAnnotation(annotations, "exact-value-" + i, "prompt", prompt, exactValues[i],
                    AnnotationType.EXACT_VALUE, true, provenance);
        }
        return new SeedCase(id, category, prompt, javaSource, oracle, constraint, evidence, split, category,
                sources, annotations);
    }

    private static void addAnnotation(List<GoldAnnotation> annotations, String id, String sourceId, String source,
                                      String text, AnnotationType type, boolean required, String provenance) {
        int start = source.indexOf(text);
        if (start < 0) {
            throw new IllegalArgumentException("fixture annotation text is not present in source " + sourceId
                    + ": " + id);
        }
        annotations.add(new GoldAnnotation(id, sourceId, start, start + text.length(), text, type, required,
                provenance, false));
    }

    /** Returns all annotation and family-split problems; callers must not silently discard these issues. */
    public static List<DatasetIssue> validateDataset(List<SeedCase> dataset) {
        Objects.requireNonNull(dataset);
        List<DatasetIssue> issues = new ArrayList<>();
        Set<String> caseIds = new HashSet<>();
        Map<String, Split> familySplits = new HashMap<>();
        for (SeedCase item : dataset) {
            if (item == null) {
                issues.add(new DatasetIssue("", "NULL_CASE", "dataset contains a null case"));
                continue;
            }
            if (!caseIds.add(item.id())) {
                issues.add(new DatasetIssue(item.id(), "DUPLICATE_CASE_ID", "case ID is not unique"));
            }
            if (item.familyId().isBlank()) {
                issues.add(new DatasetIssue(item.id(), "EMPTY_FAMILY_ID", "family ID must not be blank"));
            } else {
                Split previous = familySplits.putIfAbsent(item.familyId(), item.split());
                if (previous != null && previous != item.split()) {
                    issues.add(new DatasetIssue(item.id(), "FAMILY_SPLIT_LEAKAGE",
                            "family '" + item.familyId() + "' occurs in both " + previous + " and " + item.split()));
                }
            }
            if (!item.taskPrompt().equals(item.sources().get("prompt"))
                    || !item.javaSource().equals(item.sources().get("Seed.java"))) {
                issues.add(new DatasetIssue(item.id(), "SOURCE_MISMATCH",
                        "prompt and Seed.java source IDs must match the case's original source fields"));
            }
            Set<String> annotationIds = new HashSet<>();
            for (GoldAnnotation annotation : item.annotations()) {
                if (!annotationIds.add(annotation.id())) {
                    issues.add(new DatasetIssue(item.id(), "DUPLICATE_ANNOTATION_ID",
                            "annotation ID is not unique: " + annotation.id()));
                }
                if (annotation.id().isBlank() || annotation.sourceId().isBlank()
                        || annotation.text().isEmpty() || annotation.provenance().isBlank()) {
                    issues.add(new DatasetIssue(item.id(), "EMPTY_ANNOTATION_FIELD",
                            "annotation IDs, source IDs, text, and provenance must be non-empty"));
                    continue;
                }
                String source = item.sources().get(annotation.sourceId());
                if (source == null) {
                    issues.add(new DatasetIssue(item.id(), "UNKNOWN_SOURCE_ID",
                            annotation.id() + " refers to unknown source '" + annotation.sourceId() + "'"));
                } else if (annotation.start() < 0 || annotation.end() <= annotation.start()
                        || annotation.end() > source.length()) {
                    issues.add(new DatasetIssue(item.id(), "INVALID_ANNOTATION_OFFSETS",
                            annotation.id() + " has invalid source offsets [" + annotation.start()
                                    + "," + annotation.end() + ")"));
                } else if (!source.substring(annotation.start(), annotation.end()).equals(annotation.text())) {
                    issues.add(new DatasetIssue(item.id(), "ANNOTATION_TEXT_MISMATCH",
                            annotation.id() + " text does not equal its source substring"));
                }
            }
        }
        return List.copyOf(issues);
    }

    /** Exact, source-specific span matching; repeated text in a different source cannot satisfy a label. */
    public static Map<AnnotationType, PreservationMetrics> evaluateGold(SeedCase item, String candidate) {
        Objects.requireNonNull(item);
        Objects.requireNonNull(candidate);
        List<DatasetIssue> issues = validateDataset(List.of(item));
        if (!issues.isEmpty()) {
            throw new IllegalArgumentException("invalid evaluation case: "
                    + issues.stream().map(issue -> issue.code() + " " + issue.message()).toList());
        }
        EnumMap<AnnotationType, ConfusionCounts> counts = new EnumMap<>(AnnotationType.class);
        for (AnnotationType type : AnnotationType.values()) {
            counts.put(type, new ConfusionCounts(0, 0, 0));
        }
        Map<String, String> candidateSources = candidateSources(candidate, item.sources().keySet());
        for (GoldAnnotation annotation : item.annotations()) {
            boolean present = candidateSources.getOrDefault(annotation.sourceId(), "")
                    .contains(annotation.text());
            ConfusionCounts prior = counts.get(annotation.type());
            int tp = prior.truePositives();
            int fp = prior.falsePositives();
            int fn = prior.falseNegatives();
            if (annotation.required()) {
                if (present) {
                    tp++;
                } else {
                    fn++;
                }
            } else if (present) {
                fp++;
            }
            counts.put(annotation.type(), new ConfusionCounts(tp, fp, fn));
        }
        EnumMap<AnnotationType, PreservationMetrics> result = new EnumMap<>(AnnotationType.class);
        counts.forEach((type, value) -> result.put(type, PreservationMetrics.fromCounts(value)));
        return Map.copyOf(result);
    }

    private static Map<String, String> candidateSources(String candidate, Set<String> sourceIds) {
        Map<String, String> result = new HashMap<>();
        Matcher firstFile = FILE_MARKER.matcher(candidate);
        int promptEnd = firstFile.find() ? firstFile.start() : candidate.length();
        result.put("prompt", candidate.substring(0, promptEnd));
        for (String sourceId : sourceIds) {
            if (sourceId.equals("prompt")) {
                continue;
            }
            Pattern header = Pattern.compile("(?m)^=== FILE: " + Pattern.quote(sourceId)
                    + "(?: \\([^\\r\\n]*\\))? ===\\r?\\n");
            Matcher match = header.matcher(candidate);
            if (!match.find()) {
                result.put(sourceId, "");
                continue;
            }
            int end = candidate.length();
            Matcher nextFile = FILE_MARKER.matcher(candidate);
            if (nextFile.find(match.end())) {
                end = nextFile.start();
            }
            result.put(sourceId, candidate.substring(match.end(), end));
        }
        return result;
    }

    private static final Pattern FILE_MARKER = Pattern.compile("(?m)^=== FILE:");

    /** Provider-independent generation interface. Implementations must not log request content. */
    @FunctionalInterface
    public interface LlmClient {
        Completion complete(CompletionRequest request) throws IOException, InterruptedException;
    }

    public record CompletionRequest(String modelId, String system, String user, int maxOutputTokens,
                                   double temperature) {
        public CompletionRequest {
            Objects.requireNonNull(modelId);
            Objects.requireNonNull(system);
            Objects.requireNonNull(user);
            if (modelId.isBlank() || user.isBlank() || maxOutputTokens <= 0 || temperature < 0 || temperature > 1
                    || !Double.isFinite(temperature)) {
                throw new IllegalArgumentException("invalid completion request");
            }
        }
    }

    public record Completion(String text, Usage usage) {
        public Completion {
            Objects.requireNonNull(text);
            Objects.requireNonNull(usage);
        }
    }

    public record Usage(int inputTokens, int outputTokens, double costUsd) {
        public Usage {
            if (inputTokens < 0 || outputTokens < 0 || !Double.isFinite(costUsd) || costUsd < 0) {
                throw new IllegalArgumentException("usage values must be non-negative and finite");
            }
        }
    }

    /** All model calls require both an explicit enable flag and explicit consent. */
    public record Config(String datasetVersion, long seed, int repeats, List<String> targetModels,
                         boolean enableModelCalls, boolean explicitConsent) {
        public Config {
            Objects.requireNonNull(datasetVersion);
            targetModels = List.copyOf(targetModels);
            if (datasetVersion.isBlank() || repeats < 1 || seed < 0) {
                throw new IllegalArgumentException("invalid experiment configuration");
            }
            if (targetModels.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("target model identifiers must not be blank");
            }
            if (enableModelCalls && (!explicitConsent || targetModels.isEmpty())) {
                throw new IllegalArgumentException(
                        "model calls require explicit consent and at least one configured target model");
            }
        }

        public static Config offline(long seed) {
            return new Config(DATASET_VERSION, seed, 1, List.of(), false, false);
        }
    }

    /** Privacy-safe call metadata. It deliberately has no prompt or response text field. */
    public record UsageEvent(String modelId, String requestSha256, int inputTokens, int outputTokens,
                             long latencyMillis, double costUsd, String status) {
        public UsageEvent {
            Objects.requireNonNull(modelId);
            Objects.requireNonNull(requestSha256);
            Objects.requireNonNull(status);
            if (inputTokens < 0 || outputTokens < 0 || latencyMillis < 0 || !Double.isFinite(costUsd) || costUsd < 0) {
                throw new IllegalArgumentException("invalid usage event");
            }
        }
    }

    @FunctionalInterface
    public interface UsageLogger {
        void record(UsageEvent event) throws IOException;
    }

    /** Appends only non-content call metadata to a CSV file. */
    public static final class CsvUsageLogger implements UsageLogger {
        private final Path path;

        public CsvUsageLogger(Path path) {
            this.path = Objects.requireNonNull(path);
        }

        @Override
        public synchronized void record(UsageEvent event) throws IOException {
            Objects.requireNonNull(event);
            boolean newFile = !Files.exists(path) || Files.size(path) == 0;
            Path parent = path.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            StringBuilder row = new StringBuilder();
            if (newFile) {
                row.append("model_id,request_sha256,input_tokens,output_tokens,latency_ms,cost_usd,status\n");
            }
            row.append(csv(event.modelId())).append(',')
                    .append(csv(event.requestSha256())).append(',')
                    .append(event.inputTokens()).append(',')
                    .append(event.outputTokens()).append(',')
                    .append(event.latencyMillis()).append(',')
                    .append(String.format(Locale.ROOT, "%.8f", event.costUsd())).append(',')
                    .append(csv(event.status())).append('\n');
            Files.writeString(path, row, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        }

        private static String csv(String value) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
    }

    /** Measures a model call without logging its content; call only after config consent checks. */
    public static Completion completeLogged(Config config, LlmClient client, UsageLogger logger,
                                            CompletionRequest request) throws IOException, InterruptedException {
        Objects.requireNonNull(config);
        Objects.requireNonNull(client);
        Objects.requireNonNull(logger);
        Objects.requireNonNull(request);
        if (!config.enableModelCalls() || !config.explicitConsent()
                || !config.targetModels().contains(request.modelId())) {
            throw new IllegalStateException("model call is disabled or model is not in the consented configuration");
        }
        long start = System.nanoTime();
        try {
            Completion result = client.complete(request);
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            logger.record(new UsageEvent(request.modelId(), sha256(request.user()), result.usage().inputTokens(),
                    result.usage().outputTokens(), elapsed, result.usage().costUsd(), "success"));
            return result;
        } catch (IOException | InterruptedException e) {
            long elapsed = Duration.ofNanos(System.nanoTime() - start).toMillis();
            logger.record(new UsageEvent(request.modelId(), sha256(request.user()), 0, 0, elapsed, 0, "failure"));
            throw e;
        }
    }

    /** Deterministic evaluator callback. It must not execute arbitrary Java source in-process. */
    @FunctionalInterface
    public interface TaskChecker {
        double score(SeedCase item, String answer);
    }

    @FunctionalInterface
    public interface ExperimentArm {
        String optimize(SeedCase item, long seed, int repeat);
    }

    public record ExperimentRow(String datasetVersion, String itemId, String familyId, Split split, String arm,
                                String modelId, int repeat, int originalTokens, int optimizedTokens,
                                Map<AnnotationType, PreservationMetrics> goldMetrics, Double taskScore,
                                long optimizerLatencyMillis, String tokenizerId, boolean exact) {
        public ExperimentRow {
            Objects.requireNonNull(datasetVersion);
            Objects.requireNonNull(itemId);
            Objects.requireNonNull(familyId);
            Objects.requireNonNull(split);
            Objects.requireNonNull(arm);
            Objects.requireNonNull(modelId);
            Objects.requireNonNull(tokenizerId);
            Objects.requireNonNull(goldMetrics);
            goldMetrics = Map.copyOf(goldMetrics);
            for (AnnotationType type : AnnotationType.values()) {
                Objects.requireNonNull(goldMetrics.get(type), "missing gold metrics for " + type);
            }
            if (repeat < 0 || originalTokens < 0 || optimizedTokens < 0 || optimizerLatencyMillis < 0 || taskScore != null
                    && (!Double.isFinite(taskScore) || taskScore < 0 || taskScore > 1)) {
                throw new IllegalArgumentException("invalid experiment row");
            }
        }
    }

    /**
     * Runs optimizer-only arms offline. It measures token use and exact carrier preservation, not downstream
     * model quality. A model performance score stays null; no result is fabricated.
     */
    public static List<ExperimentRow> runOffline(Config config, List<SeedCase> dataset,
                                                 Map<String, Function<SeedCase, String>> candidateArms,
                                                 TokenCounter counter) {
        Objects.requireNonNull(candidateArms);
        Map<String, ExperimentArm> seededArms = new LinkedHashMap<>();
        candidateArms.forEach((name, arm) -> {
            Objects.requireNonNull(arm, "candidate arm must not be null");
            seededArms.put(name, (item, seed, repeat) -> arm.apply(item));
        });
        return runRepeatedOffline(config, dataset, seededArms, counter);
    }

    /**
     * Repeats offline optimizer arms using the configured seed and repeat index. The injected counter should
     * be local: this method disables generation, but cannot infer whether a custom TokenCounter uses a network.
     */
    public static List<ExperimentRow> runRepeatedOffline(Config config, List<SeedCase> dataset,
                                                         Map<String, ExperimentArm> candidateArms,
                                                         TokenCounter counter) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(dataset);
        Objects.requireNonNull(candidateArms);
        Objects.requireNonNull(counter);
        if (config.enableModelCalls()) {
            throw new IllegalArgumentException("runOffline cannot enable model calls");
        }
        if (dataset.isEmpty() || candidateArms.isEmpty()) {
            throw new IllegalArgumentException("dataset and at least one candidate arm are required");
        }
        List<DatasetIssue> datasetIssues = validateDataset(dataset);
        if (!datasetIssues.isEmpty()) {
            throw new IllegalArgumentException("invalid evaluation dataset: "
                    + datasetIssues.stream().map(issue -> issue.caseId() + " " + issue.code()
                    + " " + issue.message()).toList());
        }
        Map<String, ExperimentArm> arms = new LinkedHashMap<>();
        arms.put("original", (item, seed, repeat) -> item.originalInput());
        candidateArms.forEach((name, arm) -> {
            if (name == null || name.isBlank() || name.equals("original") || arm == null) {
                throw new IllegalArgumentException("candidate arms need nonblank unique names other than 'original'");
            }
            if (arms.putIfAbsent(name, arm) != null) {
                throw new IllegalArgumentException("duplicate arm name: " + name);
            }
        });

        List<ExperimentRow> rows = new ArrayList<>();
        for (SeedCase item : dataset) {
            TokenCount originalCount = Objects.requireNonNull(counter.count(item.originalInput()),
                    "token counter returned null");
            if (!counter.id().equals(originalCount.tokenizerId())) {
                throw new IllegalStateException("token counter ID does not match its count result");
            }
            for (Map.Entry<String, ExperimentArm> arm : arms.entrySet()) {
                for (int repeat = 0; repeat < config.repeats(); repeat++) {
                    long start = System.nanoTime();
                    String candidate = Objects.requireNonNull(
                            arm.getValue().optimize(item, config.seed(), repeat),
                            "optimization arm returned null for " + item.id());
                    long latency = Duration.ofNanos(System.nanoTime() - start).toMillis();
                    TokenCount optimizedCount = Objects.requireNonNull(counter.count(candidate),
                            "token counter returned null");
                    if (!originalCount.tokenizerId().equals(optimizedCount.tokenizerId())) {
                        throw new IllegalStateException("tokenizer changed during one experiment item");
                    }
                    rows.add(new ExperimentRow(config.datasetVersion(), item.id(), item.familyId(), item.split(),
                            arm.getKey(), "none", repeat, originalCount.tokens(), optimizedCount.tokens(),
                            evaluateGold(item, candidate), null, latency, optimizedCount.tokenizerId(),
                            originalCount.exact() && optimizedCount.exact()));
                }
            }
        }
        return List.copyOf(rows);
    }

    /** Macro averages case-level scores; micro scores are calculated from pooled TP/FP/FN counts. */
    public static List<AggregateRow> aggregateGoldMetrics(List<ExperimentRow> rows) {
        Objects.requireNonNull(rows);
        Map<AggregateKey, List<ExperimentRow>> groups = new LinkedHashMap<>();
        for (ExperimentRow row : rows) {
            AggregateKey key = new AggregateKey(row.datasetVersion(), row.arm(), row.repeat());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(row);
        }
        List<AggregateRow> aggregates = new ArrayList<>();
        for (Map.Entry<AggregateKey, List<ExperimentRow>> entry : groups.entrySet()) {
            AggregateKey key = entry.getKey();
            List<ExperimentRow> cases = entry.getValue();
            EnumMap<AnnotationType, PreservationMetrics> macro = new EnumMap<>(AnnotationType.class);
            EnumMap<AnnotationType, PreservationMetrics> micro = new EnumMap<>(AnnotationType.class);
            for (AnnotationType type : AnnotationType.values()) {
                ConfusionCounts pooled = new ConfusionCounts(0, 0, 0);
                for (ExperimentRow row : cases) {
                    pooled = pooled.plus(row.goldMetrics().get(type).counts());
                }
                PreservationMetrics pooledMetrics = PreservationMetrics.fromCounts(pooled);
                macro.put(type, new PreservationMetrics(pooled, new Scores(
                        meanDefined(cases, type, scores -> scores.precision()),
                        meanDefined(cases, type, scores -> scores.recall()),
                        meanDefined(cases, type, scores -> scores.f1()))));
                micro.put(type, pooledMetrics);
            }
            aggregates.add(new AggregateRow(key.datasetVersion(), key.arm(), key.repeat(), SummaryScope.MACRO, macro));
            aggregates.add(new AggregateRow(key.datasetVersion(), key.arm(), key.repeat(), SummaryScope.MICRO, micro));
        }
        return List.copyOf(aggregates);
    }

    private static OptionalDouble meanDefined(List<ExperimentRow> rows, AnnotationType type,
                                              Function<Scores, OptionalDouble> value) {
        double sum = 0;
        int count = 0;
        for (ExperimentRow row : rows) {
            OptionalDouble score = value.apply(row.goldMetrics().get(type).scores());
            if (score.isPresent()) {
                sum += score.getAsDouble();
                count++;
            }
        }
        return count == 0 ? OptionalDouble.empty() : OptionalDouble.of(sum / count);
    }

    private record AggregateKey(String datasetVersion, String arm, int repeat) {}

    /** Exports per-case results and macro/micro summaries without source prompts or answers. */
    public static void exportCsv(Path path, List<ExperimentRow> rows) throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(rows);
        List<String> output = new ArrayList<>();
        List<String> header = new ArrayList<>(List.of("row_type", "dataset_version", "item_id", "family_id",
                "split", "arm", "model_id", "repeat", "original_tokens", "optimized_tokens", "task_score",
                "optimizer_latency_ms", "tokenizer_id", "exact"));
        for (AnnotationType type : AnnotationType.values()) {
            String prefix = type.name().toLowerCase(Locale.ROOT);
            header.addAll(List.of(prefix + "_tp", prefix + "_fp", prefix + "_fn", prefix + "_precision",
                    prefix + "_recall", prefix + "_f1"));
        }
        output.add(String.join(",", header));
        for (ExperimentRow row : rows) {
            List<String> values = new ArrayList<>(List.of("CASE", csv(row.datasetVersion()), csv(row.itemId()),
                    csv(row.familyId()), csv(row.split().name()), csv(row.arm()), csv(row.modelId()),
                    Integer.toString(row.repeat()), Integer.toString(row.originalTokens()),
                    Integer.toString(row.optimizedTokens()), row.taskScore() == null ? "" : row.taskScore().toString(),
                    Long.toString(row.optimizerLatencyMillis()), csv(row.tokenizerId()), Boolean.toString(row.exact())));
            appendMetricValues(values, row.goldMetrics());
            output.add(String.join(",", values));
        }
        for (AggregateRow row : aggregateGoldMetrics(rows)) {
            List<String> values = new ArrayList<>(List.of(row.scope().name(), csv(row.datasetVersion()), "", "",
                    "ALL", csv(row.arm()), "none", Integer.toString(row.repeat()), "", "", "", "", "", ""));
            appendMetricValues(values, row.metrics());
            output.add(String.join(",", values));
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, output, StandardCharsets.UTF_8);
    }

    private static void appendMetricValues(List<String> values,
                                           Map<AnnotationType, PreservationMetrics> metricsByType) {
        for (AnnotationType type : AnnotationType.values()) {
            PreservationMetrics metrics = metricsByType.get(type);
            ConfusionCounts counts = metrics.counts();
            Scores scores = metrics.scores();
            values.add(Integer.toString(counts.truePositives()));
            values.add(Integer.toString(counts.falsePositives()));
            values.add(Integer.toString(counts.falseNegatives()));
            values.add(csv(scores.precision()));
            values.add(csv(scores.recall()));
            values.add(csv(scores.f1()));
        }
    }

    private static String csv(OptionalDouble value) {
        return value.isPresent() ? Double.toString(value.getAsDouble()) : "";
    }

    public static String sha256(String text) {
        Objects.requireNonNull(text);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static String csv(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }
}
