# PromptOptimizer — Research-to-Implementation Audit

**Audit date:** 2026-11-04
**Scope:** Documentation and source inspection only. No Java, build configuration, or test source was changed. No network, external model/API, or arbitrary fixture/user-code execution was used.

## 1. Executive summary

PromptOptimizer has a credible deterministic foundation for an early local prototype: source-grounded rule profiling, text segmentation, regex-protected spans, reversible prose cleanup, a tested BM25/Jaccard scorer, typed budget selection with exact assembled recounts, validation, and restore/fallback behavior. These are **partial implementations of the research design**, not a demonstrated semantic compressor. Several elements are familiar engineering methods rather than research contributions.

The newly available research files confirm that several important ideas remain proposals rather than code: TF-IDF/cosine, embeddings, RST, task-specific budget calibration, representation comparisons, structural Java compression, PDF/OCR, learned/token-level pruning, answer-quality studies, position sweeps, confidence intervals, and net-cost comparison.

The Offline Gold-Label Preservation Evaluation milestone is **not complete**. `EvaluationSupport.runRepeatedOffline` still hard-codes two generic requirement phrases and a planted-constraint substring; `SeedCase` has no stable family/gold-unit/span annotations; the configured `Config.datasetVersion()` is not propagated because each row is constructed with the global `DATASET_VERSION`; there is no family-disjoint split check; and no aggregate gold preservation, answer-quality, or net-cost evaluation exists. `EvaluationSupport.Metrics` verifies arithmetic on supplied counts, not whether those counts represent gold preservation.

Two implementation-status nuances are important:

1. **TF-IDF is not implemented.** The scorer's inverse-document-frequency term is part of BM25, not TF-IDF. There is no TF-IDF vector, cosine similarity, or corresponding baseline.
2. **The budget controller exists but is not task-adaptive.** `BudgetController.select` greedily fills a caller-provided budget using fixed type priorities and fixed priors plus BM25/Jaccard scores. It does not learn or select different per-task compression rates/policies. Thus “adaptive budget controller” is partially true only for budget-constrained selection mechanics, not adaptive policy.

### Current implementation snapshot

| Supplied status-list item | Audited status | Source evidence and qualification |
|---|---|---|
| Rule-based task profiling | PARTIALLY IMPLEMENTED | `RuleBasedTaskProfiler.profile` handles explicit labels and a narrow imperative pattern; no labeled quality evaluation. |
| BM25/Jaccard-style lexical relevance | IMPLEMENTED BUT NOT VALIDATED | `Bm25JaccardScorer.score` and tests verify deterministic mechanics; no gold relevance, recall@k, or held-out utility study. |
| Information-unit segmentation | PARTIALLY IMPLEMENTED | `ProfileSegmenter` and `ParagraphSegmenter` produce source spans; not RST-, AST-, JSON-, CSV-, or log-aware. |
| Hard-keep/protected spans | PARTIALLY IMPLEMENTED | Regex detector and budget hard-keep exist; all detected spans are HARD, coverage is not measured, and `Masker` is not a production caller. |
| Dependency-aware selection | PARTIALLY IMPLEMENTED | `DependencyGraph.infer` uses regex type references and simple colon lead-ins; not AST/symbol analysis. |
| Exact target-model tokenization | PARTIALLY IMPLEMENTED | Optional Anthropic count endpoint can return an exact result for the assembled message; the heuristic is always `exact=false`, provider transfer is optional, and full downstream context is not counted. |
| Gold-label preservation metrics | NOT IMPLEMENTED | Generic phrase/substring counts are not gold labels or precision/recall. |
| TF-IDF baseline | NOT IMPLEMENTED | No TF-IDF/cosine code or tests found; BM25 IDF is not TF-IDF. |
| Embedding-based semantic relevance | PLANNED / NOT IMPLEMENTED | Research idea appears in notes/roadmap; no embedding scorer or provider exists. |
| RST-based segmentation | DOCUMENTED ONLY | RST is discussed as a possible prior; no discourse parser, nucleus/satellite annotation, or test. |
| LLMLingua-style token pruning | PLANNED / NOT IMPLEMENTED | No token-level compressor, perplexity scorer, or matched-budget arm. |
| LongLLMLingua reverse-likelihood scoring | PLANNED / NOT IMPLEMENTED | No small-LM conditional log-probability implementation or test. |
| RepoBench-style code retrieval evaluation | DOCUMENTED ONLY / NOT IMPLEMENTED | No repository benchmark, acc@k labels, or generation evaluation. |
| Java AST-based compression | PLANNED / NOT IMPLEMENTED | No parser dependency, AST segmenter, AST transform, or structural round-trip test. |
| DeepSeek-OCR-style conversion | NOT IMPLEMENTED | No OCR, PDF extraction, visual-token representation, chart-to-table conversion, or document-type fidelity evaluation. |
| Adaptive budget controller | PARTIALLY IMPLEMENTED | Fixed-budget greedy selection exists; task/category-specific rates, calibrated policy, and adaptive controller do not. |

### Worktree and validation boundary

At audit time the project directory had **257 tracked paths marked modified or deleted** in the enclosing Git worktree. The changes include Java sources/tests and documentation, so this is not a clean baseline and the audit does not attribute those existing changes to this turn. They were preserved. The last recorded complete suite in the project history was 200 tests passing, but this audit **did not rerun tests** because the requested scope prohibits executing fixtures/user code; do not treat that historical result as verification of every current dirty source file. Only documentation links/whitespace were checked for this audit.

## 2. Resource inventory and evidence limits

| Resource | Availability and inspected content | Evidence limits |
|---|---|---|
| `PromptOptimizer — Project Knowledge Base.md` | Present, 197 lines; explicitly separates SLP3-derived book summaries from project-specific design proposals. Covers BPE, safe normalization, TF-IDF/cosine, embedding anisotropy, RST, retrieval, metrics, utility, and evaluation protocol. | Underlying SLP3 page scans/PDF are not present in the workspace; this audit inspected the knowledge-base summary, not the original pages. |
| `PromptOptimizer_Book_Concepts.docx` | Present and readable; 77 Word paragraphs. Identifies *AI Engineering: Building Applications with Foundation Models* (Chip Huyen), and explicitly labels extrapolations. Covers retrieval, evaluation, judging, cost, cache, prompt injection, exact evaluation, datasets, and missing compression-specific content. | The source book itself is not part of this workspace. The document says its prices are stale and the source has no page numbers; book claims are not independently checked. |
| `research paper filter.md` | Present, over 2,000 lines. Includes DeepSeek-OCR, RepoBench, LLMLingua, LongLLMLingua, Lost in the Middle, Prompt Compression Survey, Self-RAG, Sentence-BERT, and SWE-bench, plus cross-paper proposals and evaluation ideas. | These are paper-analysis notes, not the primary papers. Numerical claims and interpretations below are attributed to the notes and were not reproduced or independently verified. The notes themselves label proposed extensions. |
| `PromptOptimizer — Research-Driven Pipeline Blueprint (1).docx` | Present; inspected in the prior source audit. Describes the intended S0–S14 pipeline and Phases 0–6, safe/reversible-first operations, validation/recovery, and evaluation. | It contains a secondary synthesis/file-access note; source-level claims were checked against this repository, not accepted on documentation authority. |
| `REVISED_ROADMAP.md`, README, architecture/status/traceability/evaluation/security/progress docs | Present and inspected for consistency. | The previous roadmap's resource-availability paragraph was stale and is corrected by this audit. |
| Primary research papers and referenced source scans | Not present as primary papers/source scans in the workspace. | No web/network retrieval was attempted. Do not cite this audit as an independent replication or primary-source literature review. |

### How status labels are used

- **IMPLEMENTED AND VERIFIED** — relevant runtime behavior exists and tests verify that behavior; this does not automatically validate research effectiveness.
- **IMPLEMENTED BUT NOT VALIDATED** — implementation and basic mechanics exist, but no relevant effectiveness/transfer experiment has been run.
- **PARTIALLY IMPLEMENTED** — only a bounded subset or approximation exists.
- **DOCUMENTED ONLY** — idea is described but no meaningful implementation exists.
- **PLANNED** — explicitly present in an adopted roadmap, still no implementation.
- **NOT IMPLEMENTED** — no meaningful code exists; if a note proposes it, the entry says so separately.
- **NOT APPLICABLE** — technically unsuitable for the current black-box, local Java optimizer scope.
- **BLOCKED** — depends on an unresolved external requirement, such as a validated isolation boundary for untrusted code execution.

When a runtime primitive has tests but its research claim has not been measured, its status is not overstated: for example BM25 scoring mechanics can be implemented while its value as a relevance estimator remains unvalidated.

## 3. Research-to-code traceability matrix

The 48 rows below are **deduplicated concept clusters**, not 48 independent papers or a literature coverage score. Each row explains its relevance, gives source evidence (or explicitly says none), and states the adaptation/remaining work and priority. Code/test names are class-level evidence; a class/interface name alone is not taken as proof of end-to-end behavior.

| # | Resource and concept (meaning / relevance) | Exact implementation evidence | Status, scientific assessment, and next step |
|---:|---|---|---|
| 1 | SLP3 Ch. 2 — BPE/subword tokenization; necessary because visible words, identifiers, and numbers do not predict model token cost. | `HeuristicTokenCounter.count`; `AnthropicTokenCounter.count`; `HeuristicTokenCounterTest`, `AnthropicTokenCounterTest`. | **PARTIALLY IMPLEMENTED.** The local counter is a heuristic, not BPE. Optional provider counting is closer to the target tokenizer but sends the assembled text remotely. Use provider counts only when consented; do not claim exact local/model-wide usage. |
| 2 | SLP3 / AI Engineering — tokenizer fidelity by target model; necessary for a real budget guarantee. | `TokenCounter`, `HeuristicTokenCounter`, `AnthropicTokenCounter`; `PipelineDemo` chooses configured counter. | **PARTIALLY IMPLEMENTED.** Optional Anthropic count is target-specific; default heuristic is `exact=false`, and system/tool/history tokens are outside the count. Test the count contract, document transfer, and retain explicit non-exactness. |
| 3 | SLP3 — normalization and preservation of case/number/negation; matters because `US/us`, code identifiers, units, and qualifiers can change meaning. | `WhitespaceNormalizer`, `LosslessPass`, `EditScript`; `WhitespaceNormalizerTest`, `LosslessPassTest`. `Bm25JaccardScorer.tokenize` lowercases only for scoring. | **PARTIALLY IMPLEMENTED.** Reversible prose whitespace normalization is tested; no stemming/lemmatization is applied to output. Do not normalize source code/values. Add adversarial preservation tests before any more lossy normalization. |
| 4 | SLP3 / AI Engineering — TF-IDF and cosine baseline for term rarity and vector similarity. | No TF-IDF vectorizer, cosine scorer, or associated test; `Bm25JaccardScorer` is the only scorer. | **NOT IMPLEMENTED.** The scorer's BM25 IDF term is not TF-IDF, and Jaccard is not cosine. Add TF-IDF only as a transparent offline baseline if it answers a measured question. |
| 5 | SLP3 / retrieval — BM25 term weighting and document ranking; a cheap, interpretable task-to-unit retrieval baseline. | `Bm25JaccardScorer.score`, with term frequency, document frequency, length normalization and BM25 parameters; `Bm25JaccardScorerTest`. | **IMPLEMENTED BUT NOT VALIDATED.** Tests cover mechanical ranking/features, not gold relevant-unit recall or effectiveness on Java/file prompts. Keep as baseline; validate recall@k/precision@budget on labeled data before tuning weights. |
| 6 | SLP3 / Sentence-BERT — dense semantic relevance and embedding anisotropy; candidate retrieval can address lexical mismatch, but similarity does not imply safe deletion. | No embedding class, model/runtime dependency, or embedding test. | **PLANNED.** Roadmap treats embeddings as an optional experiment, but no code exists. A bi-encoder may nominate candidates only; calibrate across domains and reject merges with changed numbers, identifiers, quantifiers or negation. |
| 7 | SLP3 / AI Engineering — chunking/information units; makes selection auditable and allows task-to-unit relevance. | `ProfileSegmenter`, `ParagraphSegmenter`, `InformationUnit`; `ProfileSegmenterTest`, `ParagraphSegmenterTest`. | **PARTIALLY IMPLEMENTED.** Profile sections and paragraphs are source-grounded, but not AST, CSV-row, JSON-subtree, log-event or semantic chunks. Test exact partition and unit-level label recall per format. |
| 8 | SLP3 Ch. 24 / paper filter — RST nuclei/satellites; central propositions may deserve more budget than dependent elaboration. | No RST parser, discourse labels, or RST tests. | **DOCUMENTED ONLY.** The knowledge-base document proposes RST priors; no evidence validates an automatic parser on prompts, code, data, or logs. Keep deferred; do not use a prose discourse heuristic for exact structured content. |
| 9 | SLP3 / LongLLMLingua — task-conditioned importance `Importance(unit \| task)`; relevant units differ by request. | `RuleBasedTaskProfiler.profile`; `BudgetController.taskQuery` uses task, requirements, and output expectation text; `Bm25JaccardScorer.score`. | **PARTIALLY IMPLEMENTED.** This is lexical query conditioning, not validated semantic importance. Constraints are retained as essential but are absent from the ranking query. Add gold labels and compare task-only/query variants. |
| 10 | SLP3 / RepoBench / safety — hard-keep protected spans; exact values and constraints must not be sacrificed to token savings. | `RuleBasedProtectedSpanDetector.detect`; `BudgetController.hasHardSpan` / required-unit selection; `Masker.mask` / `unmask`; `MaskerTest`, detector and budget tests. | **PARTIALLY IMPLEMENTED.** Regex detector produces HARD spans only; `Masker` has no production caller, and the detector's recall is unmeasured. Keep conservative fallback; annotate spans and measure false negatives before expanding transforms. |
| 11 | SLP3 — utility per token and knapsack selection; allocates scarce budget to important units. | `BudgetController.select`, `value`, `priority`, `countAfterDropping`; `BudgetControllerTest`. | **PARTIALLY IMPLEMENTED.** Greedy score-density selection, fixed category priorities/priors, dependency closure, and full assembly recount are present; not a globally optimal knapsack and not learned. Validate against exact small knapsack fixtures and gold retention curves; avoid calling heuristic density “importance.” |
| 12 | SLP3 / Prompt Compression Survey — representation selection versus deletion; Markdown/JSON/table representations may have different costs and utility. | `Assembler` emits Markdown-style headers/delimiters; `PipelineDemo.formatFor` maps extensions. No representation-choice policy. | **PARTIALLY IMPLEMENTED.** Output framing is not content conversion. There are no text-to-Markdown semantic converters or JSON/CSV compaction arms. Treat re-representation as a separate matched-budget experiment with parse/round-trip and task checks. |
| 13 | SLP3 — precision, recall, Fβ and macro/micro averaging; distinguishes omissions from spurious additions across categories. | `EvaluationSupport.Metrics.calculate` and nested `Ratio`; arithmetic covered by `EvaluationSupportTest`. | **PARTIALLY IMPLEMENTED.** Formula mechanics accept caller-supplied counts; runner counts generic phrase/substrings, has no gold annotations, and does not aggregate macro/micro. Complete validated labels before reporting preservation metrics. |
| 14 | SLP3 / AI Engineering — reference-answer comparison and human adequacy/fluency; answer similarity alone is not task correctness. | `EvaluationSupport.Metrics` has optional numeric score fields; `TaskChecker` is an interface. No checker implementation or human-rating workflow. | **NOT IMPLEMENTED.** `taskScore` remains null; oracle hint is text, not a reference answer or executed oracle. Add objective checkers where safe, otherwise blinded rubric and human agreement; do not use BLEU/BERTScore as correctness. |
| 15 | SLP3 / AI Engineering — sample size, paired bootstrap, confidence intervals and noise floor; needed to tell a real effect from per-example/model variation. | No bootstrap/statistics implementation, repeated original-model arm, noise-floor estimator or analysis test. | **PLANNED.** Roadmap schedules downstream statistical analysis; the 15-item optimizer-only runner cannot estimate model variance. Predefine paired design and power/uncertainty before comparing model outcomes. |
| 16 | SLP3 / LLMLingua / research filter — matched baselines and ablations (original, no-op, random, oracle, strategy variants). | `EvaluationSupport.runOffline` builds `original` plus injected candidate arms; `EvaluationDemo` provides only `pipeline`. | **PARTIALLY IMPLEMENTED.** Two-arm CSV plumbing is present, but no random-drop, closed-book, gold-oracle, TF-IDF, ablations or matched-ratio suite. Add deterministic offline arms after gold labels, then downstream arms only with consent. |
| 17 | AI Engineering — context construction as feature engineering/context efficiency; prompt assembly choices affect downstream behavior and cost. | `Assembler.assemble`; `BudgetController.select`; stable prompt-first/file order. | **PARTIALLY IMPLEMENTED.** This is a fixed construction path, not an experiment or learned feature design. Test alternate assemblies only as isolated arms; do not assert context efficiency from reduced token counts alone. |
| 18 | AI Engineering / Lost in the Middle — long-context/NIAH probes and key-position robustness; irrelevant context/position may hurt. | No NIAH/key-value suite, position sweep, or placement metric. Assembler preserves fixed source order. | **NOT IMPLEMENTED.** Paper-note tasks are mostly factual QA and older models. Re-measure per target model and include constraints/code before adopting a reorder policy. |
| 19 | AI Engineering — hybrid retrieval, dense retrieval, reranking, contextual retrieval and RRF; can combine cheap recall with costly precision. | `Bm25JaccardScorer` only; no dense search, cross-encoder, contextual retrieval, query rewriting, or RRF. | **PARTIALLY IMPLEMENTED.** Lexical scoring is a cheap baseline, not a hybrid cascade. Introduce expensive reranking only if gold-label errors show a lexical gap and privacy/cost are acceptable. |
| 20 | AI Engineering — tabular RAG / text-to-SQL / structured-data retrieval; helps table-heavy inputs without flattening schema. | `PipelineDemo.formatFor` labels CSV/JSON structured; no parser, table-aware query, SQL, or row-selection implementation. | **NOT IMPLEMENTED.** Extension routing alone does not provide table understanding. Add parser-backed structure and row/schema invariants only for an explicitly supported task. |
| 21 | AI Engineering — cleaning, deduplication and format stripping; remove only true redundant material. | `LosslessPass` and its duplicate handling; `LosslessPassTest`; scorer has `nearDuplicateCandidates`. | **PARTIALLY IMPLEMENTED.** Exact duplicate prose handling is not semantic dedup; `nearDuplicateCandidates` returns pairs and removes nothing. False-merge testing on contradictory/near-identical cases is prerequisite to merging. |
| 22 | AI Engineering — structured output validation; malformed structure must not pass as a valid transformed representation. | `PreservationValidator.standard` includes limited structure checks; `ValidatorTest`. | **PARTIALLY IMPLEMENTED.** No Java/JSON/CSV parser validates syntax/schema or structural round-trip. Keep format-specific checks distinct and fail safe on parse errors. |
| 23 | AI Engineering / LLMLingua — entropy/perplexity as an information signal; predicts token likelihood, not task importance. | No perplexity, entropy, token log-probability or small-LM scorer. | **NOT IMPLEMENTED.** Perplexity-based pruning risks removing rare critical values and needs model/tokenizer alignment. Keep experimental and compare only against simpler selection at matched budgets. |
| 24 | AI Engineering — evaluation-driven development, component-level metrics and observability. | `EvaluationSupport`, `EvaluationDemo`, unit tests; progress history records test counts. | **PARTIALLY IMPLEMENTED.** Reproducible optimizer rows exist, but no gold labels, useful slices, task-quality metrics, or robust run manifest. First fix data validity; avoid dashboard-first work. |
| 25 | AI Engineering / Self-RAG — judge bias, dimension decomposition and human agreement; holistic scores can obscure unsupported claims. | `EvaluationSupport.LlmClient`/`completeLogged` are generic consent-gated interfaces; no judge prompts/implementation, judge test, or calibration data. | **DOCUMENTED ONLY.** A generic provider interface is not an implemented judge. If later studied, score separate relevance/support/constraint dimensions and report human agreement; do not use an optimizer's own model as sole judge. |
| 26 | AI Engineering / SWE-bench — functional correctness and exact evaluation; requirements should be checked with task-specific evidence. | `TaskChecker` interface and deterministic `PreservationValidator`; no task checker is installed by `EvaluationDemo`. | **PARTIALLY IMPLEMENTED.** Exact carried strings and structural cues are not answer correctness. Implement non-executing checks first; code tests require external sandbox validation before untrusted execution. |
| 27 | AI Engineering / Self-RAG — factual consistency, entailment, hallucination and claim-loss analysis. | No NLI/entailment, answer-claim decomposition, or hallucination scorer; validator's semantic and important-facts checks report NOT_RUN. | **NOT IMPLEMENTED.** Would require human-calibrated judgments or a carefully consented verifier. A semantic score cannot replace exact spans/requirements. |
| 28 | AI Engineering — instruction following (IFEval/INFOBench-style constraint checks). | `RuleBasedTaskProfiler` extracts explicit constraints; `PreservationValidator` has narrow exact/profile checks and task-risk rules. | **PARTIALLY IMPLEMENTED.** No comprehensive instruction-following benchmark or per-constraint answer violation score. Add canary/minimal-pair constraints to the offline gold set first. |
| 29 | AI Engineering — nondeterminism and prompt sensitivity. | `ExperimentArm` receives seed/repeat in `runRepeatedOffline`; `EvaluationSupportTest` checks seed propagation there. | **PARTIALLY IMPLEMENTED.** `EvaluationDemo` calls `runOffline`, which wraps one-argument arms and ignores seed/repeat; repeating its deterministic pipeline is not a stochastic trial. No provider variance/noise floor. |
| 30 | AI Engineering — pairwise evaluation; same input/model comparisons reduce confounds. | No paired answer comparison or paired statistical test. `runRepeatedOffline` only emits separate candidate rows. | **DOCUMENTED ONLY.** Pairing is feasible after task outcomes and correct dataset labels exist; no current comparative quality claim is supported. |
| 31 | AI Engineering — dataset quality, contamination, coverage and synthesis verification. | `EvaluationSupport.seededJavaCases`; `EvaluationSupportTest.seededDatasetHasFifteenItemsAcrossReproducibleSplits`. | **PARTIALLY IMPLEMENTED.** 15 synthetic Java cases, 11/2/2 fixed split; no family IDs, source provenance/licensing datasheet, independent label review or contamination controls. Expand and split by bug family before statistical claims. |
| 32 | AI Engineering — token cost, latency and optimizer overhead; compression only helps economically if total cost decreases. | `ExperimentRow.optimizerLatencyMillis`; `Usage`, `UsageEvent`, `CsvUsageLogger`; `EvaluationDemo` measures optimizer timing. | **PARTIALLY IMPLEMENTED.** Does not call a downstream model or combine model input/output pricing with optimizer cost, cache effects, or total cost. Report end-to-end cost only after actual consented usage data. |
| 33 | AI Engineering — prompt/exact/semantic caching; cost and latency can change with stable prefixes. | `AnthropicTokenCounter` contains an in-memory token-count cache; `CsvUsageLogger` stores hashed request metadata. | **PARTIALLY IMPLEMENTED.** This is not downstream provider prompt-prefix cache control, semantic caching, or a cache hit/miss evaluation. Don't reorder stable prefixes without measuring cache consequences. |
| 34 | AI Engineering — guardrails and prompt injection; supplied text may contain instructions hostile to the optimizer/evaluator. | No injection classifier or adversarial test suite identified; deterministic rule profiler and local CLI are not an injection defense. | **NOT IMPLEMENTED.** Treat attachments as untrusted data and never execute instructions from them. Add threat tests before any LLM classifier/judge integration. |
| 35 | AI Engineering — reflection/accept-revise; check a candidate and restore omitted material on failure. | `RecoveryController.run`, `RecoveryOutcome`, `LosslessResult.withRestored`; `RecoveryControllerTest` and property tests. | **PARTIALLY IMPLEMENTED.** Deterministic restore/fallback is real and tested; no LLM semantic reflection, token-pruning operator ladder, or validated validator recall. Retain deterministic-only semantics. |
| 36 | AI Engineering — intent classification/routing; select policies by task type. | `RuleBasedTaskProfiler.profile`; `TaskProfile` labels/warnings; task profiler tests. | **PARTIALLY IMPLEMENTED.** Explicit headers and imperative cues only; no accuracy corpus, conflict calibration, broad intent taxonomy or policy router. |
| 37 | AI Engineering / survey — fine-tuning, distillation and soft prompts/KV-token compression. | No training framework, learned compressor, model weights, or white-box target-model access; `pom.xml` only includes JUnit. | **NOT APPLICABLE** to the present black-box local Java CLI and external-model input contract. Do not add a training stack unless scope changes to owning the target model/compressor. |
| 38 | DeepSeek-OCR analysis — rendered visual-token compression and OCR reconstruction fidelity by text/formula/table/order. | No PDF/OCR/vision extractor, document reconstruction metric, or multimodal API. `PipelineDemo.formatFor` routes by extension only. | **NOT IMPLEMENTED.** Paper analysis describes OCR/reconstruction, not downstream task performance; reported numeric results are secondary notes and unverified here. Defer until PDF/scanned input and VLM target are explicit requirements. |
| 39 | DeepSeek-OCR analysis — document-type-specific representation, chart-to-table and conversion evaluation. | No PDF/layout parser, chart/table converter, or conversion arm; no JSON/CSV content parser either. | **NOT IMPLEMENTED.** Markdown delimiters from `Assembler` are not input conversion. Start with one format and reversible parse/round-trip checks, not a vision sidecar. |
| 40 | RepoBench analysis — repository retrieval, acc@k, difficulty strata, first-occurrence controls and code task metrics. | `DependencyGraph.infer` offers lexical links only; no RepoBench dataset adapter, gold snippet annotations, acc@k or generation arm. | **DOCUMENTED ONLY.** The protocol is discussed, but no RepoBench-style benchmark is implemented. Next-line completion/retrieval evidence does not establish debugging/refactoring gains. Adapt the benchmark protocol only to a task-appropriate, licensed Java set. |
| 41 | RepoBench / SWE-bench analysis — Java imports/signatures/AST, file/line oracle and placement effects. | `DependencyGraph` regexes declarations/references; `ParagraphSegmenter` segments paragraphs; no AST/parser or code position experiment. | **PARTIALLY IMPLEMENTED.** Lexical dependency closure is not repository graph or AST compression. Parser-backed source spans and recall tests should precede compact signatures/skeletons; code execution remains a separate security decision. |
| 42 | LLMLingua / survey — coarse-to-fine, component budgets, demonstration dropping and perplexity token pruning. | `BudgetController` uses typed priorities but does not prune tokens; `LosslessPass` acts on reversible prose whitespace/exact duplicate behavior. | **PARTIALLY IMPLEMENTED.** Type-aware unit selection is a coarse budget primitive, not LLMLingua. Token pruning is lossy and the analysis notes numeric/identifier corruption; keep it an isolated experiment, not default behavior. |
| 43 | LongLLMLingua — reverse likelihood `p(task \| unit)`, contrastive perplexity and conditional token scoring. | No LM log-probability client, reverse scorer, contrastive perplexity or test. | **NOT IMPLEMENTED.** Original notes describe retrieval/document tasks and flag compute/task transfer. A Python/7B sidecar would add cost/privacy/ops; only prototype if labeled data show a need. |
| 44 | LongLLMLingua / Lost in the Middle — importance-rank scheduling, reordering, subsequence recovery and position robustness. | `BudgetController` uses greedy score density; `RecoveryController` restores dropped units; `Assembler` retains stable order. No position sweeps or subsequence repair. | **PARTIALLY IMPLEMENTED.** Budget density/recovery overlap superficially, but not the paper's rank schedule, reordering, or entity repair. Test position per target/task before changing order; keep dependencies/sequences fixed. |
| 45 | Prompt Compression Survey — hard filtering vs paraphrase and soft prompt taxonomy; compare at fixed ratio or matched quality. | Current operations are mostly unit selection and reversible prose cleanup; no LLM paraphrase or soft prompt methods; evaluation has one pipeline arm. | **PARTIALLY IMPLEMENTED.** Hard filtering is a category match, not a survey method implementation. No ratio-matched method comparison. Keep lossless transformations and lossy deletion separate in future arms. |
| 46 | Self-RAG — retrieval need, IsRel, IsSup, IsUse dimension decomposition and critic calibration. | No Self-RAG model, reflective tokens, relevance/support judge, or human agreement test; `LlmClient` is only an interface. | **NOT IMPLEMENTED.** Self-RAG retrieves evidence; it does not compress user attachments. A critic is an optional costly experiment with demonstrated judge error in the paper notes, not a mandatory default. |
| 47 | SWE-bench — fail-to-pass/pass-to-pass, patch applied rate, execution-verified coding evaluation and oracle context. | Java project JUnit tests validate PromptOptimizer itself; `TaskChecker` has no implementation and seeded fixtures are not executed. | **BLOCKED** for executing supplied/generated code until an OS-level filesystem/network-isolated sandbox with resource limits is independently validated. Benchmark-compatible evaluation is otherwise NOT IMPLEMENTED. |
| 48 | Lost in the Middle / RepoBench / LongLLMLingua — position sweep, closed-book/oracle comparisons and per-target context behavior. | `SeedCase.closedBookInput` / `oracleInput` are string helpers; no task outcomes are measured with them. `EvaluationDemo` only runs original vs pipeline token counting. | **NOT IMPLEMENTED.** Helpers are not evaluation arms. Need gold oracle units, target-specific runs, controlled placements, repeat/noise-floor design and paired analysis. |

## 4. Technique-by-technique assessment: use wisely

### Implement or strengthen first (offline and low risk)

1. **Gold labels and truthful metrics.** This fixes the largest scientific flaw, and it requires no provider or parser. Annotate requirements, constraints, protected values and essential units with stable IDs and exact source spans; validate annotation consistency. Keep exact-span metrics distinct from semantic entailment.
2. **TF-IDF/cosine as a baseline only if useful.** It is absent and is directly comparable to BM25. Add only after the gold set exists, use a clearly chosen corpus/unit definition, and evaluate recall@k plus latency. No claim that it beats BM25 without held-out data.
3. **Input/segmentation robustness.** Test negation, quantifiers, values, identifiers, row order and parse failure. Add parser-backed structure only for formats the CLI explicitly promises.
4. **Budget and evaluation contract.** Fix config/version propagation, characterize deterministic repeats as deterministic, report per-item metrics and multiple caller-selected budgets, and preserve explicit infeasibility.
5. **Code dependency precision.** The regex graph is a useful low-cost baseline; AST symbol references, imports and signatures are a distinct planned improvement. Build ground-truth closure cases before integrating a parser.

### Experimental alternatives (never default without evidence)

- TF-IDF, dense embeddings/Sentence-BERT, reranking, reciprocal-rank fusion, Self-RAG-style judges, reverse-likelihood, LLMLingua/LongLLMLingua pruning, order changes/task duplication, prompt paraphrase, and visual OCR.
- Compare at a matched token budget and report preservation, downstream task score, latency, provider/compute cost, failure examples and target-model/version.
- For redundancy, label pairs as exact duplicate, paraphrase, subsumption, related-distinct, contradiction, or changed critical fact. Similarity is candidate generation, not a merge decision.
- Validate a judge against human labels per dimension. Use objective schema/function tests where possible. Avoid a single holistic quality score.

### Deliberately defer or exclude

- **Do not implement soft prompts, KV compression, fine-tuning, or distillation** while the target model is a black-box external API; these solve a different deployment problem and add training/model ownership.
- **Do not add DeepSeek-OCR or a Python vision sidecar** until PDF/scanned input is a product requirement and the target model can consume the resulting representation. OCR fidelity alone is not task success.
- **Do not make token-level pruning, LLM paraphrasing, or RST parsing default.** Each can introduce opaque semantic loss; unit selection and exact-value preservation are simpler to audit.
- **Do not use raw embedding cosine as a safety signal** or auto-merge threshold. Negation, identifiers and numbers can reverse the task while remaining highly similar.
- **Do not add an LLM judge to every optimization request.** It creates privacy, latency, prompt-injection, cost, and judge-bias issues; use only as a consented, measured experiment.
- **Do not reorder/duplicate instructions by default.** Position findings in the paper analysis concern specific older models/tasks; preserve stable ordering until target-model position sweeps show benefit and no regressions.
- **Do not execute untrusted Java** in-process or via an ordinary subprocess. A code benchmark does not justify unsafe execution; this remains blocked on external isolation validation.
- **Do not create a service/dashboard or add a large parser/model stack just to mirror the paper taxonomy.** The current user-facing scope is a small local CLI; add one format at a time based on a validated need.

## 5. Incorrect adaptations and research gaps

| Risk / claim to avoid | Audit finding |
|---|---|
| “TF-IDF exists because the code has IDF.” | False. The scorer implements BM25 IDF and Jaccard; no TF-IDF vectors/cosine. |
| “BM25/Jaccard proves semantic relevance.” | False. It is lexical retrieval with unit tests but no labeled relevance/effectiveness validation; negation and low-overlap paraphrase are unresolved. |
| “Near-duplicate candidates are removed.” | False. Candidate generation is a reporting method; it never merges/removes a unit. Exact duplicate handling is a separate prose operation. |
| “Hard constraints are comprehensively protected.” | Overstated. Regex spans are all HARD, but coverage/precision are not measured; no comprehensive semantic constraint detector. |
| “The budget controller adapts to task type.” | Overstated. It accepts a scalar budget and ranks by fixed priorities/priors. It does not learn or apply per-task budgets. |
| “Token count is exact.” | Only an optional provider count is exact for its message payload; heuristic is explicitly `exact=false`; downstream system/tool/history tokens remain outside. |
| “Gold preservation or requirement recall is measured.” | False. Generic fixed phrase/substring counts lack gold labels and denominators; `Metrics` formulas do not repair their inputs. |
| “Dataset version and repeated runs are controlled.” | Incomplete. Row construction uses the global dataset constant; `EvaluationDemo` uses `runOffline`, whose wrapper ignores seed/repeat and reexecutes deterministic arms. |
| “Synthetic evaluation demonstrates real quality.” | False. Fifteen synthetic Java items and optimizer token rows have no model answers or task scores. |
| “Paper values are project results.” | False. The paper filter is a secondary analysis and explicitly distinguishes paper findings from proposals; no primary paper verification/reproduction was performed. |
| “RepoBench/SWE-bench directly validates Java debugging compression.” | Not established. Task type, dataset, model, context format and success criteria differ; adapt protocol and validate transfer rather than copy headline rates. |
| “OCR reconstruction or answer similarity equals task correctness.” | False. Reconstruction/overlap are proxies; exact values, code behavior and user constraints require dedicated checks. |
| “Consent helper covers every outbound path.” | False. `EvaluationSupport.completeLogged` gates its injected client, but separate `LlmTaskProfiler`/Kaggle classification and optional Anthropic counting have their own external data paths. |
| “Passing JUnit tests proves compressed Java works.” | False. Tests validate optimizer implementation; no arbitrary fixture execution or sandbox exists. |

The papers and book notes primarily concern QA, retrieval, summarization, model prompting or specific older target models. Their protocol ideas transfer more safely than their effect sizes. Any future numerical target must be set from project-specific evaluation or verified primary evidence.

## 6. Research contribution assessment

| Hypothesis | Current state | Testable with current code? | Evidence gap / contribution potential |
|---|---|---|---|
| 1. Task-conditioned unit importance | PARTIALLY IMPLEMENTED: lexical query uses task, requirements, outputs. | A basic scorer-vs-baseline test is possible; valid retention evaluation needs gold labels. | No empirical support yet. A contribution would require calibrated held-out unit ranking and category transfer, not merely adding a task query. |
| 2. Risk-aware exact-information preservation | PARTIALLY IMPLEMENTED: regex HARD spans and restore checks. | Mechanistic minimal-pair tests possible now; coverage-rate claim needs labeled spans. | Candidate contribution only if risk classes and false-negative rates are explicit and shown to preserve downstream behavior. |
| 3. Task-type-specific budgets | NOT IMPLEMENTED beyond fixed type priorities and per-call budget. | Requires task categories and budget curves; current data are insufficient. | Potentially useful but the optimal policy cannot be learned from 15 labels or guessed weights. |
| 4. Representation selection vs deletion | NOT IMPLEMENTED; Markdown assembly is output framing, not semantic re-representation. | No current representation arms. | Could be valuable for text/JSON/CSV/code, but requires parse/round-trip and task-quality tests per format. |
| 5. Compression-fidelity curves by category | NOT IMPLEMENTED as an evaluation result. | CLI can accept a budget; repeated configured runs could generate a curve after gold labels. | Essential empirical result. Must report preservation and downstream quality separately, with adequate sample sizes. |
| 6. Position-aware preservation | NOT IMPLEMENTED; assembler keeps deterministic order. | Needs controlled placement variants and downstream model/checker. | Paper-filter findings do not establish effects on current target models, code, or hard constraints. |
| 7. Cross-model transfer of importance estimates | NOT IMPLEMENTED. | Not with current offline optimizer-only runner. | Requires multiple target models and frozen scorer, matching budgets, cost, and transfer analysis. |
| 8. Preservation-guarded optimization/recovery | PARTIALLY IMPLEMENTED and unit-tested for deterministic checks/restoration. | Mechanism tests exist; validator recall and semantic effectiveness are untested. | Engineering foundation, not novel evidence. A meaningful result would quantify prevented loss and false acceptance. |
| 9. Net-cost optimization including overhead | PARTIALLY IMPLEMENTED fields/logging, NOT implemented as a net-cost result. | Requires downstream usage/cost data not available offline. | Potential contribution only after optimizer compute/API and downstream costs, latency and cache effects are paired. |

### Standard engineering versus possible research contribution

Strict parsing, task labels, BM25/Jaccard retrieval, protected spans, provenance, fixed-budget selection, exact recounts, deterministic validation, and rollback are useful engineering patterns. Their combination is not by itself a novelty claim.

Potentially research-worthy questions include whether task-conditioned importance beats generic lexical ranking at matched budgets; whether risk labels improve exact fact/constraint retention; whether task-specific budgets generalize; whether re-representation beats deletion; and whether deterministic preservation metrics predict downstream task success. The current project has not tested these hypotheses, and the supplied notes do not establish novelty in the broader literature. Perform a primary-literature comparison before using “novel” or “first.”

## 7. Prioritized improvement plan

Ranking favors evidence quality and low-risk feasibility before algorithm complexity.

### A. Offline gold-label preservation evaluation — best next milestone

- **Objective/motivation:** replace substring proxies with valid per-item requirement, constraint, exact-value/protected-span, and essential-unit measurements.
- **Reuse/change:** extend `EvaluationSupport.SeedCase`/dataset and `ExperimentRow`; correct `runRepeatedOffline`; add nested annotation/metric types in `EvaluationSupport`; update `EvaluationDemo` output. No dependency or new framework is required.
- **Tests:** source span bounds/text mismatch; gold and candidate ID matching; false positives/negatives; empty denominators; `datasetVersion` propagation; family-disjoint split; deterministic repeat metadata; CSV reconciliation to hand calculations.
- **Metrics/acceptance:** per-item requirement/constraint precision/recall/Fβ, exact protected-value recall, essential-unit recall, token count, exactness flag, dataset/split/version. All annotations map to exact source spans. No generic fixed-phrase or planted-substring metric. `taskScore` remains null.
- **Dependencies:** current synthetic items can seed the schema, but a small set is not a representative benchmark; labels need review and case families before general claims.
- **Claim boundary:** establishes deterministic carrier/preservation measurements only; does not establish semantic entailment, compression quality, answer performance, or net cost.

### B. Honest offline comparisons and robustness

- **Objective:** make baseline and budget effects visible before model evaluation.
- **Reuse/change:** `EvaluationSupport.runOffline`, `EvaluationDemo`, `Bm25JaccardScorer`, `BudgetController`; add no-op, random seeded, lexical baseline variants and user-specified budget sweep output after A.
- **Tests/metrics:** reproducible arms, fixed token-budget checks, selected-unit recall@budget, candidate false-merge tests, exact values/negation/order, length buckets and dense/noisy input strata.
- **Acceptance:** held-out family split, scorer vs random/no-op/original, multiple budget points, no claims based on one point; all arms share data and token counter.
- **Claim boundary:** supports unit-carrier and token-efficiency comparisons only; still no downstream answer quality.

### C. CLI and supported-format reliability

- **Objective:** assert actual arguments, errors, file/output behavior and declared format boundaries end to end.
- **Reuse/change:** `PipelineDemo`, `Canonicalizer`, `formatFor`, `Assembler`; integration tests in existing test tree.
- **Tests/metrics:** malformed UTF-8, max size, unsupported/binary file, extension mapping, output-not-written on validation/infeasibility failure, per-file byte preservation and source mapping.
- **Acceptance:** supported formats explicit; unsupported ones fail visibly; no silent parse/conversion claims.
- **Claim boundary:** reliable CLI behavior, not semantic compression quality.

### D. TF-IDF baseline and calibrated retrieval/selection

- **Objective:** answer whether a simpler TF-IDF cosine baseline provides a useful contrast to BM25/Jaccard.
- **Reuse/change:** separate scorer implementation and `Bm25JaccardScorerTest`-style tests, only after A/B labels exist; preserve default policy until evidence.
- **Tests/metrics:** hand-computed vectors/cosines, empty vectors, ranking tie cases, recall@k, precision@budget, latency, category-held-out results; compare simple Jaccard/BM25/TF-IDF.
- **Acceptance:** fit thresholds/weights only on train/dev; report test performance and errors; no TF-IDF label for existing BM25 code.
- **Claim boundary:** retrieval ranking quality only, not semantic redundancy or downstream task performance.

### E. Parser-backed code/structured units

- **Objective:** replace regex Java dependency guesses with AST source boundaries and explicitly supported format parsers.
- **Reuse/change:** `DependencyGraph`, `ParagraphSegmenter`, `BudgetController`, `PreservationValidator`; evaluate parser feasibility before adding a dependency.
- **Tests/metrics:** imports, declarations, inheritance/calls, ambiguous symbols, malformed code fail-safe, dependency closure, parse/round-trip fidelity; corresponding JSON/CSV tests separately.
- **Acceptance:** no dangling labeled dependency; exact-source mappings; parse failure does not silently summarize; report parser coverage.
- **Claim boundary:** structural completeness/syntax preservation, not runtime equivalence. SWE-bench-like execution is blocked pending sandbox validation.

### F. Downstream task-quality study, then optional advanced methods

- **Objective:** measure whether preserved prompt units improve answers and whether compression is worth total cost.
- **Reuse/change:** `EvaluationSupport` consent interface and row model; implement explicit, safe task checkers or blinded human rubric. Provider adapters require separate approval.
- **Tests/metrics:** original, closed-book, oracle, no-op, random, lossless, BM25, and optimized at matched budgets; per-target model paired scores, repeat/noise floor, intervals, category curves, model/tokenizer, latency and end-to-end cost.
- **Acceptance:** pre-registered non-inferiority/noise floor; human-checker agreement; no content leakage in logs; full cost includes optimizer overhead. External model use must be explicit and consented.
- **Claim boundary:** applies only to evaluated tasks/model/version; do not generalize to all Claude prompts.

### G. Optional experiments and production controls

- **Objective:** test embeddings, reverse likelihood, token pruning, OCR or service deployment only after earlier evidence shows a gap.
- **Tests/metrics:** each as isolated matched-budget arm; sensitive-data/latency/compute; exact-value and contradiction failure sets. Production requires threat model and data lifecycle.
- **Acceptance:** show incremental value over simpler baselines and no protected-content regressions. Execution requires independently reviewed OS isolation.
- **Claim boundary:** no feature becomes default from paper headline results alone.

## 8. Ten highest-priority implemented research-inspired mechanics

These are implementation mechanics, **not claims of effectiveness**:

1. **Rule-based task/item extraction:** `RuleBasedTaskProfiler.profile`, `RuleBasedTaskProfilerTest`.
2. **Source-grounded profile segmentation:** `ProfileSegmenter.segment`, `ProfileSegmenterTest`.
3. **Paragraph-based source units:** `ParagraphSegmenter.segment`, `ParagraphSegmenterTest`.
4. **Protected-span detection and hard retention:** `RuleBasedProtectedSpanDetector.detect`, budget-selection tests; detector coverage remains unmeasured.
5. **Reversible prose whitespace edits:** `WhitespaceNormalizer`, `EditScript`, `WhitespaceNormalizerTest`.
6. **Exact duplicate handling:** `LosslessPass`, `LosslessPassTest`; does not deduplicate semantic near-matches.
7. **BM25 ranking:** `Bm25JaccardScorer.score`, `Bm25JaccardScorerTest`; not TF-IDF and not empirically calibrated.
8. **Jaccard/directional overlap candidate features:** `Bm25JaccardScorer.compare` / `nearDuplicateCandidates`; no destructive merge.
9. **Typed budget and exact assembly recount:** `BudgetController.select` / `countAfterDropping`, `BudgetControllerTest`; greedy fixed priors, not adaptive policy or exact knapsack.
10. **Deterministic validation and recovery:** `PreservationValidator.standard`, `RecoveryController.run`, `RecoveryControllerTest`; semantic/important-fact/probe checks remain NOT_RUN.

Additional real mechanism: `AnthropicTokenCounter` can optionally count the assembled user message with the provider; it is a network transfer and not the local default quality/evaluation setup.

## 9. Ten most important missing capabilities

1. Gold labels with source spans/family IDs for constraints, protected values and essential units.
2. Valid per-case precision/recall and macro/micro aggregation, replacing generic substring counts.
3. Correct dataset-version propagation, family-disjoint splits and truthful repeat semantics.
4. Downstream answer-quality checker/reference and paired original/closed-book/oracle evaluation.
5. Sufficient dataset size/diversity, annotation audit, contamination/licensing provenance and noise floor.
6. Multiple matched-budget baselines, ablations and retention-versus-compression curves.
7. Separate TF-IDF/cosine baseline and calibrated BM25/Jaccard effectiveness.
8. Task-specific risk/budget policy calibration; distinguish this from existing scalar-budget greedy selection.
9. Parser-backed Java/JSON/CSV/log segmentation, dependency closure and structural validation.
10. Position robustness, net-cost accounting and human-calibrated judge/semantic metrics; these need task-quality infrastructure first.

Other non-MVP gaps include OCR/PDF conversion, embedding/SBERT, Self-RAG critics, LongLLMLingua reverse-likelihood and LLMLingua token pruning.

## 10. Deliberately not implement now

- Soft prompts/KV compression, prompt tuning, finetuning or distillation of an owned compressor.
- Default LLM-based prompt rewriting or judge calls on all user inputs.
- Automatic embedding-based near-duplicate merging without contradiction/exact-value guards.
- Token-level pruning or reverse-likelihood in the default path.
- RST parser/prior as a universal importance signal across code, logs, tables and constraints.
- OCR/DeepSeek-style sidecar before PDF/scanned input and VLM consumption are confirmed requirements.
- Reordering or duplicating user instructions by default based on older model position curves.
- Executing arbitrary Java tasks before independently validated host isolation and resource control.
- REST service, dashboard, or broad parser dependencies absent a product need.

“Not now” is not a claim that the technique can never help; it is a decision to avoid unmeasured cost, risk, and complexity.

## 11. Concept coverage estimate and method

The matrix contains **48 deduplicated concept clusters** selected from the three available research/design documents and the blueprint. Each cluster counts once even if multiple sources repeat it; papers' separate metrics and the same underlying retrieval concept are consolidated. The status distribution is:

| Status bucket | Count | Share | Interpretation |
|---|---:|---:|---|
| IMPLEMENTED BUT NOT VALIDATED | 1 | 2.1% | BM25 implementation mechanics exist and tests exercise them; usefulness is still not validated. |
| PARTIALLY IMPLEMENTED | 27 | 56.3% | A concrete subset/approximation exists, but material pieces or validation are missing. |
| DOCUMENTED ONLY / PLANNED | 6 | 12.5% | Ideas or protocols are explicitly described/scheduled, but have no corresponding implementation. |
| NOT IMPLEMENTED | 12 | 25.0% | No meaningful code exists for the concept cluster and the row is not counted as documented/planned. |
| BLOCKED | 1 | 2.1% | Execution-verified untrusted-code benchmark path awaits external OS isolation validation. |
| NOT APPLICABLE | 1 | 2.1% | Fine-tuning/soft prompts do not fit the black-box external-model/local CLI scope. |
| **Total** | **48** | **100%** | A descriptive inventory, not a quality or project-completion score. |

For the table count, each of the 48 rows is assigned once by its stated primary status; explicit optional/planned items count in DOCUMENTED ONLY / PLANNED, while unimplemented ideas without an adopted plan count as NOT IMPLEMENTED. Percentages are rounded independently, so the displayed values may sum to 100.1%. This estimate is sensitive to clustering and should not be read as “56% of the research is implemented.” Many partial rows are only baseline mechanics. The single implemented scoring cluster does not mean effective retrieval is established. The more relevant conclusion: **gold-label preservation and downstream quality remain unmeasured**.

## 12. Copilot prompt for the best next milestone

> Implement only the “Offline Gold-Label Preservation Evaluation” milestone. Before editing, inspect the current `EvaluationSupport`, `EvaluationDemo`, evaluation tests, and current dirty Git state. Preserve all unrelated changes. Do not change optimizer runtime behavior, modify dependencies, call any external API/model, access the network, or execute fixture/user code.
>
> Add stable source-grounded gold annotations to the existing synthetic evaluation cases: case and family IDs; required items; constraints; exact values/protected spans; and relevant information units. Validate each annotation’s source ID, bounds, and exact text. Replace the two fixed generic requirement phrase checks and planted-constraint substring check with explicit gold-ID/span-based per-case precision/recall and exact-preservation measures, with documented empty/undefined denominator behavior. Keep the existing downstream `taskScore` null.
>
> Fix `runRepeatedOffline` to write the configured dataset version rather than the global constant. Ensure the dataset split is family-disjoint and tested. Keep `EvaluationDemo` accurately described: its current `runOffline` arm is deterministic and ignores seed/repeat; either report deterministic duplicate runs truthfully or adapt the runner without implying stochastic model trials. Do not add model calls.
>
> Extend only the existing evaluation tests where practical: invalid span/text, hand-calculated TP/FP/FN, empty gold/prediction behavior, family leakage, configured version propagation, deterministic repeats, and CSV row reconciliation. Update `EVALUATION_GUIDE.md`, `REQUIREMENT_TRACEABILITY.md`, `IMPLEMENTATION_STATUS.md`, `REVISED_ROADMAP.md` if needed, and append a truthful `PROGRESS.md` entry. Avoid file sprawl.
>
> Run the focused evaluation tests and full Maven suite. Do not execute arbitrary Java fixture/user code, make provider calls, or change source outside the evaluation milestone. Report exact files and test results, and leave downstream model-quality, budget-curve, TF-IDF, AST, provider, and sandbox work for later milestones. Do not claim semantic equivalence, downstream quality, or research novelty.
