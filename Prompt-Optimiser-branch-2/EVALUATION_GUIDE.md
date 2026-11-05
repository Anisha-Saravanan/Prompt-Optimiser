# Offline Evaluation Guide

## What this evaluation measures

`EvaluationDemo` runs the local optimizer on 15 synthetic Java-debugging fixtures (`seeded-java-debug-v1`). Each fixture has a stable case ID, a family ID, a split, source text keyed by source ID, and source-offset annotations for selected task information, requirements, constraints, exact values, and protected spans. The current category-derived family IDs are singletons; family-split validation is implemented, but this dataset is too small to support family generalization claims.

The fixture annotation provenance says it reflects seed-fixture intent and is **not independently human-reviewed**. An annotation is not silently treated as valid: its source ID, non-empty offsets, exact source substring, unique ID, and family/split assignment are validated before a run. Invalid datasets cause an explicit error. Do not describe the current labels as human-verified gold.

## Reproduce

From the project directory:

```powershell
mvn -Dtest=EvaluationSupportTest test
mvn test
java -cp target\classes org.promptoptimizer.evaluation.EvaluationDemo --help
java -cp target\classes org.promptoptimizer.evaluation.EvaluationDemo `
  --output target\evaluation\seeded-java-debug-v1.csv `
  --seed 17 `
  --repeats 1
```

The demo uses `HeuristicTokenCounter` (`exact=false`), makes no model calls, runs no Java fixtures, and leaves `task_score` blank. The configured dataset version is written on every case and summary row. `runOffline` wraps one-argument deterministic arms: repeating them checks reproducibility, not stochastic model variability. Seed/repeat are recorded; a repeated identical deterministic run is not an independent trial.

The CSV contains one row per case/arm/repeat and macro/micro summary rows for each arm/repeat, with separate metrics per annotation type. It contains IDs and counts/scores, not prompt or answer text. Summary rows are derived from the same case-level counts used in the case rows.

## Annotation contract and matching

Each `GoldAnnotation` contains an annotation ID, source ID, half-open character offsets `[start,end)`, exact source text, annotation type, a `required` positive/negative label, provenance, and a `humanReviewed` flag. Source IDs map to the original prompt or `Seed.java`; annotations must match `source.substring(start,end)` exactly. The runner validates all annotations and rejects unknown source IDs, invalid ranges, source-text mismatches, duplicate case/annotation IDs, blank family IDs, and a family appearing in more than one split.

An annotation is predicted present only when its exact text occurs in the corresponding source's region in the assembled candidate. For file annotations, the matching region is located by its `=== FILE: <source-id> ... ===` header. The same phrase in another source does not satisfy the label. This is literal, case-sensitive carrier matching; there is no semantic or paraphrase matcher. Repeated occurrences within the correct source count once for that annotation.

Metrics are calculated independently by annotation type:

- **TP:** required annotation text occurs in its corresponding candidate source.
- **FN:** required annotation text does not occur in its corresponding candidate source.
- **FP:** an explicitly annotated `required=false` span occurs in its corresponding candidate source.
- **Precision:** `TP / (TP + FP)`.
- **Recall:** `TP / (TP + FN)`.
- **F1:** `2TP / (2TP + FP + FN)`.

For example, with 2 required spans found, 1 explicitly non-required span found, and 1 required span missing: `TP=2`, `FP=1`, `FN=1`; precision, recall and F1 are each `2/3`.

If a denominator is zero, the corresponding score is empty/undefined—not a fabricated zero or one. F1 is empty when its denominator is zero; if there are gold positives but none predicted, F1 is zero. **Unannotated candidate text is not automatically an FP.** Consequently, precision is meaningful only to the extent that negative spans and the relevant item inventory have been annotated. The current seed set is mainly positive-only, so its precision values must not be interpreted as a complete assessment of irrelevant retained content.

**Exact protected-span preservation** is the `PROTECTED_SPAN` type's exact-source carrier result (including TP/FN and recall). The same explicit matching rule applies; no fuzzy similarity is used. It assesses only supplied annotations and does not measure detector coverage.

## Aggregation and split rules

- **Per-case:** each case row includes counts and precision/recall/F1 for every annotation type.
- **Macro:** arithmetic mean of the per-case score values that are defined for that annotation type and metric. Undefined case metrics are omitted from that metric's mean; the sample count is not currently exported.
- **Micro:** sum case TP/FP/FN first, then calculate precision/recall/F1 from the pooled counts.
- **Splits:** a family ID may occur in only one of TRAIN, DEV, or TEST. A leakage is a validation error, not a warning.
- **Repeats:** output rows remain traceable to case, family, split, version, arm and repeat. Repeated deterministic optimizer runs do not quantify model variance or increase the independent dataset size.

## What is and is not established

`EvaluationSupport.Metrics.calculate` retains formula helpers for token reduction, remaining-token ratio, compression ratio, requirement/constraint formulas when counts are supplied, and optional downstream performance metrics. The gold runner now computes source-grounded exact-span metrics; it does not calculate downstream answer quality.

The evaluation framework's existence does **not** show optimizer effectiveness. The current synthetic labels have not received independent review, only 15 fixtures exist, each current family is a singleton, exact string matching cannot credit a valid paraphrase, and the annotation inventory does not fully characterize irrelevant output. Token counts are heuristic and non-exact. No task checker/reference answer, answer-quality score, model comparison, confidence interval, net-cost result, or effectiveness claim is produced.

Before making a preservation or effectiveness claim:

1. Have domain reviewers verify source-grounded labels and explicitly label relevant and non-relevant units; record reviewer/provenance and adjudication.
2. Expand cases into meaningful repeated task families, freeze family-disjoint train/dev/test assignments, and keep a held-out test set untouched during tuning.
3. Measure exact-carrier limitations separately from any future documented semantic rubric or checker.
4. Compare no-op, random, BM25-only, Jaccard-only, and combined selection at matched actual token budgets; report per-family results and uncertainty.
5. Add downstream answer-quality and net-cost experiments only with valid checkers/reference answers and explicit consent. Keep `task_score` null until then. Do not execute arbitrary Java source in-process.

See [REQUIREMENT_TRACEABILITY.md](./REQUIREMENT_TRACEABILITY.md) for phase evidence and [REVISED_ROADMAP.md](./REVISED_ROADMAP.md) for the next milestone.
