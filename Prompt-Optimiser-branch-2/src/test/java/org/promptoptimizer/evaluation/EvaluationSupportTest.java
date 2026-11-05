package org.promptoptimizer.evaluation;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.promptoptimizer.evaluation.EvaluationSupport.AnnotationType;
import org.promptoptimizer.evaluation.EvaluationSupport.Config;
import org.promptoptimizer.evaluation.EvaluationSupport.GoldAnnotation;
import org.promptoptimizer.evaluation.EvaluationSupport.ExperimentRow;
import org.promptoptimizer.evaluation.EvaluationSupport.Input;
import org.promptoptimizer.evaluation.EvaluationSupport.Metrics;
import org.promptoptimizer.evaluation.EvaluationSupport.PreservationMetrics;
import org.promptoptimizer.evaluation.EvaluationSupport.SeedCase;
import org.promptoptimizer.evaluation.EvaluationSupport.Split;
import org.promptoptimizer.evaluation.EvaluationSupport.SummaryScope;
import org.promptoptimizer.evaluation.EvaluationSupport.Usage;
import org.promptoptimizer.evaluation.EvaluationSupport.UsageEvent;
import org.promptoptimizer.token.HeuristicTokenCounter;

import static org.junit.jupiter.api.Assertions.*;

class EvaluationSupportTest {
    @TempDir
    java.nio.file.Path temp;

    @Test
    void seededDatasetHasFifteenItemsAcrossReproducibleSplits() {
        List<SeedCase> first = EvaluationSupport.seededJavaCases();
        List<SeedCase> second = EvaluationSupport.seededJavaCases();

        assertEquals(15, first.size());
        assertEquals(first, second);
        assertEquals(15, first.stream().map(SeedCase::id).distinct().count());
        assertTrue(EvaluationSupport.validateDataset(first).isEmpty());
        assertTrue(first.stream().anyMatch(item -> item.split() == EvaluationSupport.Split.TRAIN));
        assertTrue(first.stream().anyMatch(item -> item.split() == EvaluationSupport.Split.DEV));
        assertTrue(first.stream().anyMatch(item -> item.split() == EvaluationSupport.Split.TEST));
        for (SeedCase item : first) {
            assertTrue(item.originalInput().contains(item.plantedConstraint()));
            assertTrue(item.oracleInput().contains(item.oracleHint()));
            assertFalse(item.closedBookInput().contains("=== FILE:"));
            assertTrue(item.annotations().stream().noneMatch(GoldAnnotation::humanReviewed));
            assertTrue(item.annotations().stream().allMatch(annotation ->
                    item.sources().get(annotation.sourceId()).substring(annotation.start(), annotation.end())
                            .equals(annotation.text())));
        }
    }

    @Test
    void datasetValidationReportsUnknownSourcesInvalidOffsetsAndMismatchedText() {
        String prompt = "alpha beta";
        SeedCase invalid = caseWith("bad", "family", Split.TRAIN, prompt, "class Seed {}",
                List.of(
                        annotation("unknown", "missing", 0, 5, "alpha", AnnotationType.REQUIREMENT, true),
                        annotation("offset", "prompt", -1, 4, "alpha", AnnotationType.CONSTRAINT, true),
                        annotation("text", "prompt", 0, 5, "omega", AnnotationType.EXACT_VALUE, true)));

        var issues = EvaluationSupport.validateDataset(List.of(invalid));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("UNKNOWN_SOURCE_ID")));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("INVALID_ANNOTATION_OFFSETS")));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("ANNOTATION_TEXT_MISMATCH")));
        assertThrows(IllegalArgumentException.class, () -> EvaluationSupport.runRepeatedOffline(
                Config.offline(1), List.of(invalid), Map.of("copy", (item, seed, repeat) -> item.originalInput()),
                new HeuristicTokenCounter()));
    }

    @Test
    void exactMatchMetricsCalculateHandCheckedTrueFalseAndMissingCounts() {
        String source = "alpha beta gamma red blue";
        SeedCase item = caseWith("metrics", "metrics-family", Split.TEST, source, "class Seed {}",
                List.of(annotation("a", "prompt", 0, 5, "alpha", AnnotationType.REQUIREMENT, true),
                        annotation("b", "prompt", 6, 10, "beta", AnnotationType.REQUIREMENT, true),
                        annotation("c", "prompt", 11, 16, "gamma", AnnotationType.REQUIREMENT, true),
                        annotation("d", "prompt", 17, 20, "red", AnnotationType.REQUIREMENT, false),
                        annotation("e", "prompt", 21, 25, "blue", AnnotationType.REQUIREMENT, false)));

        PreservationMetrics metrics = EvaluationSupport.evaluateGold(item, "alpha beta red")
                .get(AnnotationType.REQUIREMENT);
        assertEquals(2, metrics.counts().truePositives());
        assertEquals(1, metrics.counts().falsePositives());
        assertEquals(1, metrics.counts().falseNegatives());
        assertEquals(2.0 / 3.0, metrics.scores().precision().orElseThrow());
        assertEquals(2.0 / 3.0, metrics.scores().recall().orElseThrow());
        assertEquals(2.0 / 3.0, metrics.scores().f1().orElseThrow());
    }

    @Test
    void emptyAnnotationsAndZeroDenominatorsAreExplicitlyUndefined() {
        SeedCase item = caseWith("empty", "empty-family", Split.DEV, "prompt", "class Seed {}", List.of());
        PreservationMetrics metrics = EvaluationSupport.evaluateGold(item, item.originalInput())
                .get(AnnotationType.PROTECTED_SPAN);

        assertEquals(0, metrics.counts().truePositives());
        assertEquals(0, metrics.counts().falsePositives());
        assertEquals(0, metrics.counts().falseNegatives());
        assertTrue(metrics.scores().precision().isEmpty());
        assertTrue(metrics.scores().recall().isEmpty());
        assertTrue(metrics.scores().f1().isEmpty());
    }

    @Test
    void exactProtectedSpanMustRemainInItsAnnotatedSource() {
        String source = "class Seed { String token = \"needle\"; }";
        GoldAnnotation protectedSpan = annotation("protected", "Seed.java", source.indexOf("\"needle\""),
                source.indexOf("\"needle\"") + "\"needle\"".length(), "\"needle\"",
                AnnotationType.PROTECTED_SPAN, true);
        SeedCase item = caseWith("span", "span-family", Split.TEST, "TASK: inspect the file",
                source, List.of(protectedSpan));

        var misplaced = EvaluationSupport.evaluateGold(item, "\"needle\"\n=== FILE: Seed.java ===\n");
        var retained = EvaluationSupport.evaluateGold(item,
                "TASK: inspect the file\n\n=== FILE: Seed.java ===\n" + source);
        assertEquals(1, misplaced.get(AnnotationType.PROTECTED_SPAN).counts().falseNegatives());
        assertEquals(0, misplaced.get(AnnotationType.PROTECTED_SPAN).counts().truePositives());
        assertEquals(1, retained.get(AnnotationType.PROTECTED_SPAN).counts().truePositives());
        assertEquals(0, retained.get(AnnotationType.PROTECTED_SPAN).counts().falseNegatives());
    }

    @Test
    void seededExactValuesAndConstraintsAreScoredFromTheirDeclaredSource() {
        SeedCase item = EvaluationSupport.seededJavaCases().stream()
                .filter(testCase -> testCase.id().equals("java-002")).findFirst().orElseThrow();
        var metrics = EvaluationSupport.evaluateGold(item, item.originalInput());

        assertEquals(1, metrics.get(AnnotationType.EXACT_VALUE).counts().truePositives());
        assertEquals(1, metrics.get(AnnotationType.CONSTRAINT).counts().truePositives());
        assertEquals(1, metrics.get(AnnotationType.PROTECTED_SPAN).counts().truePositives());
    }

    @Test
    void rejectsFamilyLeakageAcrossDatasetSplits() {
        SeedCase train = caseWith("train", "same-family", Split.TRAIN, "a", "class Seed {}", List.of());
        SeedCase test = caseWith("test", "same-family", Split.TEST, "b", "class Seed {}", List.of());

        var issues = EvaluationSupport.validateDataset(List.of(train, test));
        assertTrue(issues.stream().anyMatch(issue -> issue.code().equals("FAMILY_SPLIT_LEAKAGE")));
        assertThrows(IllegalArgumentException.class, () -> EvaluationSupport.runRepeatedOffline(
                Config.offline(1), List.of(train, test), Map.of("copy", (item, seed, repeat) -> item.originalInput()),
                new HeuristicTokenCounter()));
    }

    @Test
    void computesTokenRequirementConstraintAndPerformanceMetricsFromBlueprintFormulas() {
        Metrics metrics = Metrics.calculate(new Input(200, 100, 4, 3, 3, 2, 1, 1,
                0.8, 0.72, 0.2, 0.9, 0.01));

        assertEquals(50, metrics.tokenReductionPercent());
        assertEquals(0.5, metrics.remainingTokenRatio());
        assertEquals(2, metrics.compressionRatio().orElseThrow());
        assertEquals(0.75, metrics.requirement().recall().orElseThrow());
        assertEquals(1, metrics.requirement().precision().orElseThrow());
        assertEquals(0.5, metrics.constraint().recall().orElseThrow());
        assertEquals(1, metrics.constraint().precision().orElseThrow());
        assertEquals(6.0 / 7.0, metrics.requirement().fBeta().orElseThrow());
        assertEquals(5.0 / 9.0, metrics.constraint().fBeta().orElseThrow());
        assertEquals(90, metrics.performanceRetentionPercent().orElseThrow());
        assertEquals((0.72 - 0.2) / (0.9 - 0.2), metrics.oracleNormalizedScore().orElseThrow());
        assertEquals(180, metrics.optimizationEfficiency().orElseThrow());
    }

    @Test
    void undefinedMetricsStayExplicitInsteadOfInventingZeroScores() {
        Metrics metrics = Metrics.calculate(new Input(0, 0, 0, 0, 0, 0, 0, 0,
                0.5, 0.5, 0.5, 0.5, 0));

        assertEquals(0, metrics.tokenReductionPercent());
        assertEquals(1, metrics.remainingTokenRatio());
        assertTrue(metrics.compressionRatio().isEmpty());
        assertTrue(metrics.requirement().recall().isEmpty());
        assertTrue(metrics.requirement().precision().isEmpty());
        assertTrue(metrics.performanceRetentionPercent().isPresent());
        assertTrue(metrics.oracleNormalizedScore().isEmpty());
    }

    @Test
    void metricInputsRejectImpossibleMatchCounts() {
        assertThrows(IllegalArgumentException.class, () -> new Input(1, 1, 1, 0, 1, 0, 0, 0,
                null, null, null, null, 0));
    }

    @Test
    void offlineRunnerComparesOriginalAndCandidateWithoutFabricatingTaskScores() {
        List<SeedCase> data = EvaluationSupport.seededJavaCases().subList(0, 2);
        Config config = new Config("custom-gold-v2", 17, 1, List.of(), false, false);
        var rows = EvaluationSupport.runOffline(config, data,
                Map.of("keep-original-and-add-context",
                        item -> item.originalInput().replace("class Seed", "// context\nclass Seed")),
                new HeuristicTokenCounter());

        assertEquals(4, rows.size());
        assertEquals(2, rows.stream().filter(row -> row.arm().equals("original")).count());
        assertTrue(rows.stream().allMatch(row -> row.taskScore() == null));
        assertTrue(rows.stream().allMatch(row -> row.datasetVersion().equals("custom-gold-v2")));
        ExperimentRow candidate = rows.stream().filter(row -> row.arm().equals("keep-original-and-add-context"))
                .findFirst().orElseThrow();
        assertEquals(2, candidate.goldMetrics().get(AnnotationType.REQUIREMENT).counts().truePositives());
        assertEquals(1, candidate.goldMetrics().get(AnnotationType.CONSTRAINT).counts().truePositives());
        assertEquals(2, candidate.goldMetrics().get(AnnotationType.INFORMATION_UNIT).counts().truePositives());
        assertEquals(2, rows.stream().map(ExperimentRow::itemId).distinct().count());
        assertThrows(IllegalArgumentException.class, () -> EvaluationSupport.runOffline(
                new Config(EvaluationSupport.DATASET_VERSION, 1, 1, List.of("model"), true, true),
                data, Map.of("candidate", SeedCase::originalInput), new HeuristicTokenCounter()));
    }

    @Test
    void repeatedRunnerPassesConfiguredSeedAndRecordsEveryRepeat() {
        List<Long> observedSeeds = new ArrayList<>();
        Config config = new Config("repeat-gold-v3", 27, 3, List.of(), false, false);
        var rows = EvaluationSupport.runRepeatedOffline(config,
                EvaluationSupport.seededJavaCases().subList(0, 1),
                Map.of("candidate", (item, seed, repeat) -> {
                    observedSeeds.add(seed);
                    return item.originalInput();
                }), new HeuristicTokenCounter());

        assertEquals(6, rows.size());
        assertEquals(List.of(0, 1, 2), rows.stream().map(ExperimentRow::repeat).distinct().toList());
        assertEquals(List.of(27L, 27L, 27L), observedSeeds);
        assertTrue(rows.stream().allMatch(row -> row.taskScore() == null));
        assertTrue(rows.stream().allMatch(row -> row.datasetVersion().equals("repeat-gold-v3")));
        List<ExperimentRow> candidateRows = rows.stream().filter(row -> row.arm().equals("candidate")).toList();
        assertEquals(candidateRows.get(0).goldMetrics(), candidateRows.get(1).goldMetrics());
        assertEquals(candidateRows.get(1).goldMetrics(), candidateRows.get(2).goldMetrics());
        assertEquals(candidateRows.get(0).optimizedTokens(), candidateRows.get(1).optimizedTokens());
        assertEquals(candidateRows.get(1).optimizedTokens(), candidateRows.get(2).optimizedTokens());
    }

    @Test
    void modelCallsRequireExplicitConsentAndAConfiguredModel() throws Exception {
        AtomicBoolean called = new AtomicBoolean();
        var request = new EvaluationSupport.CompletionRequest("model-a", "", "private prompt", 100, 0);
        var client = (EvaluationSupport.LlmClient) ignored -> {
            called.set(true);
            return new EvaluationSupport.Completion("answer", new Usage(3, 1, 0.01));
        };
        var logger = (EvaluationSupport.UsageLogger) ignored -> {};

        assertThrows(IllegalStateException.class, () -> EvaluationSupport.completeLogged(
                Config.offline(1), client, logger, request));
        assertFalse(called.get());
        var consented = new Config(EvaluationSupport.DATASET_VERSION, 1, 1, List.of("model-a"), true, true);
        var completion = EvaluationSupport.completeLogged(consented, client, logger, request);
        assertEquals("answer", completion.text());
        assertTrue(called.get());
    }

    @Test
    void usageCsvAndExperimentExportNeverIncludePromptOrAnswerText() throws IOException {
        var usagePath = temp.resolve("usage.csv");
        var logger = new EvaluationSupport.CsvUsageLogger(usagePath);
        logger.record(new UsageEvent("model-a", EvaluationSupport.sha256("sensitive prompt"),
                10, 2, 40, 0.01, "success"));
        String usage = Files.readString(usagePath);
        assertFalse(usage.contains("sensitive prompt"));
        assertFalse(usage.contains("answer"));
        assertTrue(usage.contains("request_sha256"));

        var resultPath = temp.resolve("results.csv");
        SeedCase item = caseWith("csv", "csv-family", Split.TRAIN, "alpha beta", "class Seed {}",
                List.of(annotation("required", "prompt", 0, 5, "alpha", AnnotationType.REQUIREMENT, true),
                        annotation("optional", "prompt", 6, 10, "beta", AnnotationType.REQUIREMENT, false)));
        var gold = EvaluationSupport.evaluateGold(item, "alpha");
        var row = new ExperimentRow("csv-gold-v1", "csv", "csv-family", Split.TRAIN, "candidate", "none",
                0, 12, 8, gold, null, 5, "test-tokenizer", false);
        EvaluationSupport.exportCsv(resultPath, List.of(row));
        List<String> exported = Files.readAllLines(resultPath);
        String[] header = exported.get(0).split(",", -1);
        String[] caseRow = exported.get(1).split(",", -1);
        Map<String, Integer> column = new java.util.HashMap<>();
        for (int i = 0; i < header.length; i++) {
            column.put(header[i], i);
        }
        assertEquals("CASE", caseRow[column.get("row_type")]);
        assertEquals("\"csv-gold-v1\"", caseRow[column.get("dataset_version")]);
        assertEquals("1", caseRow[column.get("requirement_tp")]);
        assertEquals("0", caseRow[column.get("requirement_fp")]);
        assertEquals("0", caseRow[column.get("requirement_fn")]);
        assertEquals("1.0", caseRow[column.get("requirement_precision")]);
        assertEquals("1.0", caseRow[column.get("requirement_recall")]);
        assertEquals("1.0", caseRow[column.get("requirement_f1")]);
        assertTrue(exported.stream().anyMatch(line -> line.startsWith("MACRO,\"csv-gold-v1\"")));
        assertTrue(exported.stream().anyMatch(line -> line.startsWith("MICRO,\"csv-gold-v1\"")));
        String allRows = String.join("\n", exported);
        assertFalse(allRows.contains("TASK:"));
        assertFalse(allRows.contains("sensitive prompt"));
    }

    @Test
    void macroAndMicroSummariesAreComputedFromPerCaseCounts() {
        SeedCase first = caseWith("first", "first-family", Split.TRAIN, "alpha beta red", "class Seed {}",
                List.of(annotation("a", "prompt", 0, 5, "alpha", AnnotationType.REQUIREMENT, true),
                        annotation("b", "prompt", 6, 10, "beta", AnnotationType.REQUIREMENT, true),
                        annotation("red", "prompt", 11, 14, "red", AnnotationType.REQUIREMENT, false)));
        SeedCase second = caseWith("second", "second-family", Split.TEST, "beta blue", "class Seed {}",
                List.of(annotation("b", "prompt", 0, 4, "beta", AnnotationType.REQUIREMENT, true),
                        annotation("blue", "prompt", 5, 9, "blue", AnnotationType.REQUIREMENT, false)));
        List<ExperimentRow> rows = EvaluationSupport.runOffline(Config.offline(4), List.of(first, second),
                Map.of("partial", item -> item.id().equals("first") ? "alpha" : "blue"),
                new HeuristicTokenCounter()).stream().filter(row -> row.arm().equals("partial")).toList();
        var summaries = EvaluationSupport.aggregateGoldMetrics(rows);
        var macro = summaries.stream().filter(row -> row.scope() == SummaryScope.MACRO).findFirst().orElseThrow();
        var micro = summaries.stream().filter(row -> row.scope() == SummaryScope.MICRO).findFirst().orElseThrow();

        assertEquals(0.5, macro.metrics().get(AnnotationType.REQUIREMENT).scores().precision().orElseThrow());
        assertEquals(0.25, macro.metrics().get(AnnotationType.REQUIREMENT).scores().recall().orElseThrow());
        assertEquals(1.0 / 3.0, macro.metrics().get(AnnotationType.REQUIREMENT).scores().f1().orElseThrow());
        assertEquals(1, micro.metrics().get(AnnotationType.REQUIREMENT).counts().truePositives());
        assertEquals(1, micro.metrics().get(AnnotationType.REQUIREMENT).counts().falsePositives());
        assertEquals(2, micro.metrics().get(AnnotationType.REQUIREMENT).counts().falseNegatives());
        assertEquals(0.4, micro.metrics().get(AnnotationType.REQUIREMENT).scores().f1().orElseThrow());
    }

    private static GoldAnnotation annotation(String id, String sourceId, int start, int end, String text,
                                             AnnotationType type, boolean required) {
        return new GoldAnnotation(id, sourceId, start, end, text, type, required, "test fixture", true);
    }

    private static SeedCase caseWith(String id, String family, Split split, String prompt, String javaSource,
                                     List<GoldAnnotation> annotations) {
        return new SeedCase(id, "test", prompt, javaSource, "oracle", "constraint", "evidence", split, family,
                Map.of("prompt", prompt, "Seed.java", javaSource), annotations);
    }
}
