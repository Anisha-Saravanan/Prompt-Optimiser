# Code Quality and Optimization Audit

## 1. Executive verdict

**The Java project compiles and its current Maven test suite passes. It is not yet ready to demonstrate research effectiveness or reliable Claude-limit enforcement.**

- `mvn clean test` completed successfully against an isolated copy of the current `pom.xml` and `src/` tree: **207 tests, 0 failures, 0 errors, 0 skipped**. This avoided running `clean` against the live worktree, where tracked `target/` outputs were already dirty.
- The default CLI is a deterministic, rule-based pipeline. It does not use an LLM to rewrite prompts. Its offline token count is explicitly non-exact, so it cannot guarantee a hard Claude context limit.
- Several core components are implemented and tested, but a budget-priority ordering issue can favor optional content over important content. The preservation detector also has regex coverage gaps.
- The current evaluation is a useful offline framework, not effectiveness evidence. Its optimizer-vs-original token comparison does not use identical file framing; the measured run produced **1,906 original tokens versus 1,994 pipeline tokens** (heuristic; **88 more tokens, or 4.62% growth**). That result is not a valid estimate of compression because the inputs being compared are serialized differently.
- No source answers are executed, no downstream answer quality is measured, and the synthetic annotations are not independently human-reviewed.

The audit did not edit Java sources or dependency manifests. The worktree was already dirty from earlier project work; build outputs under `target/` should be treated as generated artifacts, not as clean source evidence.

## 2. Scope and method

Reviewed all 39 production Java files and 23 test Java files, `pom.xml`, the README and architecture/evaluation documents, and the current research audit documents. Ran the full Maven clean-test lifecycle in an isolated copy, the offline evaluation demo, the segmentation demo, and the pipeline CLI help path. No external model/API call or Java fixture execution was made.

The primary runtime and evaluation classes are linked throughout this report. Test results establish compilation and specified behavior; they do not establish that the heuristics improve model answers or generalize.

## 3. Actual execution flow

The standard CLI is `PipelineDemo`:

1. Parse prompt, attachment, output, budget, context-window, and reserve options; reject missing/oversized/non-regular input files and duplicate attachment names.
2. Select `AnthropicTokenCounter` when configured, otherwise use `HeuristicTokenCounter`.
3. Canonicalize UTF-8 prompt and attachment text, normalize line endings, and assign attachment format from its extension.
4. Profile the prompt with `RuleBasedTaskProfiler`.
5. Segment attachments with `ParagraphSegmenter`; segment the prompt with `ProfileSegmenter`.
6. Detect regex/lexicon hard spans using `RuleBasedProtectedSpanDetector`.
7. Prepare documents and run `RecoveryController.standard`.
8. The recovery path runs lossless normalization/deduplication, selects eligible units under a budget if supplied, assembles and recounts candidates, validates structural/protected-content requirements, and restores units or tries less-lossy/lossless/original fallbacks.
9. Reject infeasible budgets without writing a candidate; otherwise return assembled text and report token-count exactness and warnings.

Relevant entry points: [PipelineDemo](./src/main/java/org/promptoptimizer/PipelineDemo.java), [Canonicalizer](./src/main/java/org/promptoptimizer/ingest/Canonicalizer.java), [RuleBasedTaskProfiler](./src/main/java/org/promptoptimizer/task/RuleBasedTaskProfiler.java), [ProfileSegmenter](./src/main/java/org/promptoptimizer/segment/ProfileSegmenter.java), [ParagraphSegmenter](./src/main/java/org/promptoptimizer/segment/ParagraphSegmenter.java), [RuleBasedProtectedSpanDetector](./src/main/java/org/promptoptimizer/protect/RuleBasedProtectedSpanDetector.java), [LosslessPass](./src/main/java/org/promptoptimizer/lossless/LosslessPass.java), [BudgetController](./src/main/java/org/promptoptimizer/optimize/BudgetController.java), [Assembler](./src/main/java/org/promptoptimizer/assemble/Assembler.java), [PreservationValidator](./src/main/java/org/promptoptimizer/validate/PreservationValidator.java), and [RecoveryController](./src/main/java/org/promptoptimizer/recover/RecoveryController.java).

The `.java`, `.json`, and `.csv` routes are still text processing, not language/data parsing. JSON/CSV are labeled structured data but do not receive schema-aware optimization or conversion. The CLI writes whatever assembled text it produces; choosing a `.md` output extension does not convert an uploaded file into Markdown.

## 4. Technique-by-technique audit

| Technique | Implementation and pipeline status | Correctness, tests, and limits |
|---|---|---|
| Task profiling | `RuleBasedTaskProfiler` is connected to the CLI and evaluation runner. `LlmTaskProfiler` is a separate optional path. | Rule/heading-based; tested, but not a semantic task classifier. The LLM path is not the standard pipeline. |
| Information segmentation | `ProfileSegmenter` and `ParagraphSegmenter` are connected. | Tests verify partitions and fenced-code cases. Paragraphs/headings are proxies for information units; they do not establish semantic completeness. |
| Protected-span detection | `RuleBasedProtectedSpanDetector` is connected; overlapping spans are merged. | Tests cover constraints, numbers, identifiers, paths, errors, and quoted text. It is regex/lexicon-based and does not parse Java ASTs. It misses some important value forms (see findings). |
| Masking and restoration | `BudgetController` masks hard-span regions for lexical scoring using its own helper. | The standalone `Masker` has tests but is not instantiated in the production path. Its presence should not be mistaken for integrated masking/restoration. |
| Lossless whitespace normalization | `WhitespaceNormalizer` and `LosslessPass` are connected. | Reversible edit records and reconstruction are tested. Source code and fenced blocks receive special handling. “Lossless” describes recoverability/audit records; transformations still change delivered text. |
| Exact duplicate removal | Implemented by `LosslessPass` for eligible repeated units; omission markers and token costs are considered. | Tests exercise duplicate handling. Exact duplicate removal is not the same as semantic or near-duplicate removal; the marker can offset savings. |
| BM25 scoring | `Bm25JaccardScorer` is called by `BudgetController`; query terms come from the task/requirements/outputs. | Uses word-like terms, term frequency, document-length normalization and BM25-style IDF, then request-local score normalization. Formula tests exist; no calibrated corpus or effectiveness evidence. |
| Jaccard similarity | Also in `Bm25JaccardScorer`; combines set overlap with BM25. | Lowercased token sets; empty/empty returns zero. The 50/50 default blend is heuristic. The standalone near-duplicate candidate method is not used to remove content in the main pipeline. |
| Dependency closure | `DependencyGraph` supplies prerequisite closure to budget selection. | Helps keep referenced units together, but inference is lexical rather than AST/type-complete. Tests cover selection mechanics, not arbitrary Java dependency correctness. |
| Budget-based selection | `BudgetController` tests candidate assembled size after proposed drops and uses deterministic density/tie ordering. | The pass order currently visits `OPTIONAL` before `IMPORTANT` (finding 1). Benefit weights are hand-set, not learned or validated. |
| Token counting | `AnthropicTokenCounter` optionally uses the provider token-count endpoint; `HeuristicTokenCounter` is the offline default. | Exactness is surfaced. The heuristic is not a tokenizer and its “upper bound” is not a proved bound. Thus offline budget checks are estimates, not hard guarantees. |
| Prompt assembly | `Assembler` emits prompt-first text, file headers/line counts, omission markers, and provenance. | Assembly, empty inputs, provenance, and formatting have tests. Its file framing differs from the evaluation runner’s original arm, invalidating direct token comparisons there. |
| Preservation validation | `PreservationValidator` checks budget, structure/provenance, protected spans, exact values and dependencies. | Supported checks are tested. Semantic/important-fact and model-probe checks are explicitly `NOT_RUN`; a passing result is not proof of meaning preservation. |
| Recovery/fallback | `RecoveryController` evaluates candidate plans, restores content where possible, and falls back through less-lossy, lossless-only, or original delivery. | Tests include failure paths and property cases. If nothing safe fits, it reports infeasibility and does not deliver a partial candidate. |
| Gold-label evaluation | `EvaluationSupport` validates source-offset annotations, matches exact text in the matching source, exports case and aggregate metrics, and leaves `taskScore` null. | Focused tests pass. Labels are fixture-authored, not independently human-reviewed; the current set is small and family-singleton. Metric/evaluation limitations are in findings below. |

## 5. Algorithmic correctness findings

### 5.1 Ranked bugs and correctness risks

| Priority | Finding | Evidence and consequence |
|---|---|---|
| **High** | Optional units can consume the budget before important units. | `BudgetController.select` iterates `Priority.values()`, and the enum order is `OPTIONAL, IMPORTANT`; `CONTEXT`, `NOISE`, and `REPEAT` map to optional. Each pass fills the remaining budget before the next starts. A tight budget can therefore keep lower-value content and exclude a relevant `DOC`/`CODE` unit. Add a regression test where a compact important unit competes with a larger optional unit. |
| **High** | The evaluation token baseline and pipeline candidate use different serialization. | `SeedCase.originalInput()` emits `=== FILE: Seed.java ===`, while the optimizer counts/assembles using the standard `Assembler` header with line count. In the offline run, 15 original rows totaled 1,906 heuristic tokens and 15 pipeline rows totaled 1,994. The resulting **-4.62% “reduction”** is confounded by framing; it cannot establish savings or lack of savings. Measure both arms through the same assembler and count the exact final model input. |
| **High** | Offline token counts cannot guarantee staying under the target model limit. | `HeuristicTokenCounter` estimates characters/runs with a fixed factor and sets `exact=false`; the formula is not a proven upper bound for every text/tokenizer. The CLI correctly warns users, but any claim of guaranteed context fit must require exact provider counts or a validated conservative bound. |
| **Medium** | Some version identifiers are not hard-protected. | The detector’s `NUMBER` pattern forbids a preceding word character or dot, so the digits in `v1.2.3` do not match; the other identifier patterns do not match this form either. If such a value occurs in a non-cued unit, budget selection can drop it and the same detector will not flag its loss. Add coverage for version, signed, currency, and other domain-critical formats. |
| **Medium** | `HeuristicTokenCounter` accepts `NaN`. | The constructor only checks `safetyFactor < 1.0`; this comparison is false for `NaN`. `Math.ceil(raw * NaN)` followed by conversion to `int` yields zero, which can make non-empty content appear free and defeat the budget check. Require a finite factor and test `NaN`/infinities. |
| **Medium** | Precision is not a meaningful measure of all retained/unwanted content in the current seed set. | `evaluateGold` counts false positives only when an explicitly `required=false` annotation’s exact text is present. The seeded fixtures are primarily positive annotations; arbitrary unannotated filler is not enumerated as a false positive. Therefore the output’s precision is conditional on a sparse annotation inventory, not general “relevance precision.” |
| **Medium** | The evaluation macro CSV row pairs macro scores with pooled counts. | `aggregateGoldMetrics` puts pooled TP/FP/FN into the macro `PreservationMetrics`, while its scores are means of per-case scores. CSV then exports those counts alongside macro precision/recall/F1. These values do not form one confusion matrix and can mislead readers. Separate macro score fields from pooled counts or omit counts on macro rows. |
| **Low** | Repeated deterministic evaluation does not estimate variability. | The current code correctly documents this. Repeats rerun deterministic arms and should be reported as reproducibility checks, not independent samples or stochastic performance. |

### 5.2 Specific algorithm checks

- **BM25:** The implementation uses document term counts, document length, average length and an IDF term of the BM25 form `log(1 + (N - df + 0.5)/(df + 0.5))`. It applies query-term frequency and normalizes scores within the current request. This is a plausible lexical ranking implementation, but the query, normalization and default blend are heuristics; tests establish arithmetic behavior, not relevance quality.
- **Jaccard:** Tokens are lowercased and converted to sets, so repeated terms do not increase overlap. Empty/empty is defined as zero. The scorer blends this with BM25; no ablation shows the blend helps.
- **Budget selection:** Candidate cost is recalculated from reassembled text, and dependencies are closed before consideration. Tie ordering is deterministic. Main defect: priority passes are reversed for the current intended importance policy. If protected content plus framing exceeds budget, recovery returns infeasibility rather than delivering an unsafe partial result.
- **Protected spans:** Offsets are document-relative, overlaps are unioned, and the longest overlapping span supplies its kind/reason while hard protection wins. The regex detector is not AST-aware, and exact values not recognized by its patterns cannot be enforced by the same validator.
- **Recovery:** Candidate validation failures are recorded and the controller attempts restoration/fallback. An original fallback is only deliverable if it fits a configured budget. Runtime failures in candidate paths trigger fallback rather than silently accepting a modified result; the outcome/audit records status. Fail-closed infeasibility is safer than returning a partial candidate.

## 6. Tests and execution results

### Executed in this audit

| Command/check | Result |
|---|---|
| `mvn clean test` on isolated copy of current `pom.xml` and `src/` | **PASS** — 207 tests, 0 failures, 0 errors, 0 skipped; 39 production and 23 test source files compiled. Isolated because cleaning the live worktree would remove tracked/dirty `target/` outputs. |
| `PipelineDemo --help` | **PASS** — displayed CLI options and the non-exact-tokenizer warning. No prompt or API request was made. |
| `SegmenterDemo` | **PASS** — canonicalized line endings, emitted one partition, and reported partition verification passed. |
| `EvaluationDemo --seed 17 --repeats 1` | **PASS as an execution**, not as evidence of effectiveness — emitted 30 per-case rows and macro/micro summaries; tokenizer was `heuristic-upper-bound-v1`, `exact=false`; `task_score` remained blank. |

The test suite includes unit coverage for segmentation, normalization, duplicate handling, scoring, budget selection, protection, recovery, assembly/validation, and gold evaluation. `PipelineIntegrationTest` is included in the 207-test Maven run.

### Measurement from the offline evaluation

The run used the 15 seeded Java-debugging fixtures and no budget. Across 15 original rows, the counter reported 1,906 tokens. Across 15 pipeline rows, it reported 1,994 tokens: **88 more tokens** (calculated as `(1906 - 1994) / 1906 = -4.62%`). The pipeline rows had 30/30 exact requirement annotations and 15/15 exact constraint annotations present; those are counts for this seeded set, not human-validated preservation rates. The tokenizer was non-exact.

This measurement is not a baseline comparison: original and candidate file headers differ, and the run does not include separate lossless-only or duplicate-only arms. It has no matched-budget result and no downstream answer checker. Do not report the negative reduction as a property of the optimizer itself.

### Requested realistic-case and baseline coverage gaps

- Existing tests cover Java snippets, Markdown fences, repeated text, empty input, CSV-shaped text, malformed structural markers, and infeasible budget/recovery paths.
- There is no comprehensive end-to-end benchmark for long prompts with irrelevant background, surrounding constraints, negative values, currencies/dates/version identifiers, JSON/CSV semantics, malformed file encodings, or repeated CLI optimization under matched budgets.
- The project does not expose separate “cleanup only” and “exact duplicate removal only” evaluation arms. Therefore baseline B and C were **not run**; inventing measurements or adding algorithms/harness code was outside this audit-only task.
- No answer-quality, cost, or model comparison is available. The integration/test suite exercises local components, not external provider effectiveness.

## 7. Preservation and recovery

The strongest reliability properties are architectural: candidates are reassembled and recounted, provenance/structure is checked, protected text is compared exactly, failed transformations can restore units, and infeasible budgets produce no deliverable. These are meaningfully tested.

The limits are equally important:

- The validator does not semantically prove that important facts, negation, or task intent survived; those checks are explicitly not run.
- Rule-based spans can miss uncued constraints, code identifiers, signed values, and version formats.
- The standalone `Masker` is not wired into the main pipeline, although the scorer has a separate masking helper.
- Recovery can guarantee only the checks actually implemented, and an estimated counter cannot guarantee exact provider limits.
- `.java`, `.json`, and `.csv` files are treated as text. No AST-based compression or structured data conversion is implemented.

## 8. Code quality and documentation

The code is divided into small typed components, uses deterministic ordering in key paths, reports non-exact counting explicitly, and has substantial focused tests. The only declared application dependency is JUnit for tests; `pom.xml` does not declare runtime libraries.

The optional `AnthropicTokenCounter` can send content to the provider token-count endpoint when configured. Because the same counter is used during segmentation, optimization, and assembly, calls may include individual unit text as well as assembled text. Make this data flow explicit to users and avoid enabling it without consent. `LlmTaskProfiler`/`categorize_agent` are separate external-classification paths and are not part of the default CLI.

Some documentation is stale relative to the implementation: the README and `RESEARCH_IMPLEMENTATION_AUDIT.md` still describe gold-label evaluation as absent/incomplete, while `EVALUATION_GUIDE.md` documents the newer implementation and its limitations. The research audit should be reconciled before using these documents as a single project status source. No unrelated documentation was edited as part of this audit.

## 9. Recommended fixes, in order

1. Reverse or otherwise explicitly define budget priority ordering; add a regression test proving important content is selected ahead of optional content under a constrained budget.
2. Make evaluation arms serialize the original and candidate identically, then add offline-only cleanup and duplicate-only arms. Report exact input/output text hashes and tokenizer exactness with each case.
3. Fix and test finite token-counter factor validation. Treat the heuristic as an estimate unless a defensible, target-specific upper bound is established.
4. Expand and review protected-value patterns, including `v1.2.3`, negative/signed values, and relevant currency/unit forms; add tests where a non-cued unit competes for budget.
5. Expand gold annotations into reviewed, repeated task families with explicit negative/irrelevant labels. Keep split assignment family-disjoint and preserve the current `taskScore = null` behavior.
6. Correct aggregate CSV semantics so macro scores cannot be read alongside unrelated pooled confusion counts.
7. Update stale status docs, and document exactly what text is sent when external token counting or classification is enabled.
8. Only after these items, run matched-budget ablations and a valid downstream answer-quality evaluation with reference answers/checkers. Add semantic compression methods only after current baselines and safety metrics are trustworthy.

## 10. Features not ready to implement

Do not prioritize embedding-based compression, RST segmentation, LLMLingua/LongLLMLingua pruning, Java AST compression, OCR conversion, adaptive budget control, or external LLM-as-judge evaluation yet. The codebase lacks validated, apples-to-apples savings evidence, independently reviewed labels, and downstream quality checks to tell whether such complexity helps or causes damage. First fix selection priority and evaluation validity.

## 11. Research demonstration / viva readiness

**Ready for a bounded engineering demonstration of the deterministic pipeline, its audit/recovery behavior, and its offline gold-label framework. Not ready for a research effectiveness demonstration or claims that it reliably optimizes prompts for Claude.**

For a viva, distinguish clearly between implemented heuristics and validated benefits. Show the 207-test pass, explain the non-exact token-count caveat, disclose the priority-order and evaluation-baseline issues, and state that seed labels are not independently reviewed and no downstream answer-quality result exists. A defensible effectiveness claim requires corrected matched baselines, reviewed family-disjoint annotations, model-exact or validated counting, and a valid downstream checker.
