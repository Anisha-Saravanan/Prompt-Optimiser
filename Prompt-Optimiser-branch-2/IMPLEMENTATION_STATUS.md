# PromptOptimizer — Implementation Status

## Executive status

**Maturity: research prototype / local CLI, not a production service.** The current code can build an offline prompt from UTF-8 text inputs, retain a conservative subset under an optional token budget, validate selected deterministic properties, and report fallback or infeasibility. It does not demonstrate semantic equivalence or downstream task-quality preservation.

The official roadmap is Phases 0–6. The earlier “milestones” in [PROGRESS.md](./PROGRESS.md) are historical development labels, not the blueprint phases.

| Phase | Status | Source-level evidence and remaining work |
|---|---|---|
| 0 — Evaluation skeleton | PARTIALLY IMPLEMENTED | Fifteen synthetic fixtures now carry source-ID/offset annotations and exact-carrier TP/FP/FN metrics, per-case rows, macro/micro summaries, family-split validation and configured version propagation. The labels are fixture-authored, not independently human-reviewed; families are singletons; downstream checker, sandbox, model scores and statistical claims remain absent. |
| 1 — Reliable baseline | PARTIALLY IMPLEMENTED | Strict UTF-8 canonicalization, extension-based format mapping, local rule profiler, paragraph/profile segmentation, regex protected spans, reversible prose cleanup, assembly, deterministic checks and fallback exist. No content-sniffed extractors, JSON/CSV/PDF parsers, structural round-trip validation, REST layer or broad malformed-input/fuzz corpus. |
| 2 — Information-aware optimization | PARTIALLY IMPLEMENTED | Custom BM25/Jaccard scoring, typed unit selection, conservative essential-type retention, regex dependency closure, exact prose deduplication and restore-only recovery exist. Java dependencies are not AST-derived; near-duplicate candidates are not merged; no Java AST, CSV summary or log transformations; score priors and thresholds are uncalibrated. |
| 3 — Advanced strategies | NOT IMPLEMENTED | No embedding scorer, directional semantic verifier, LLMLingua/LongLLMLingua adapter, reverse-likelihood scorer, PDF sidecar or token-pruning operator. |
| 4 — Evaluation framework | PARTIALLY IMPLEMENTED | The 15-item offline runner exports source-grounded exact-span metrics with case-level traceability and macro/micro summaries. It does not run the blueprint’s experimental arms, score task outputs, compute paired statistics, cost savings, confidence intervals, position sweeps or quality curves; label review and meaningful repeated families are still required. |
| 5 — Adaptive optimization | NOT IMPLEMENTED | No calibrated policy table, held-out policy evaluation, per-category curves or trained selector. |
| 6 — Production hardening | PARTIALLY IMPLEMENTED | The CLI has per-file size limits and strict UTF-8 handling; default optimizer execution is local. No service authentication, access control, retention/deletion, rate limiting, full secrets scanning, job system, production observability or safe execution sandbox. |

Status definitions: **IMPLEMENTED** means the named behavior exists in the actual runtime path and has relevant tests; **PARTIALLY IMPLEMENTED** means only an identified subset is present; **NOT IMPLEMENTED** means absent; **EXPERIMENTAL** means optional/non-default and not accepted as a production guarantee; **BLOCKED BY EXTERNAL VALIDATION** means a required external security or operational boundary has not been demonstrated.

## Actual runtime boundaries

- `PipelineDemo` uses `RuleBasedTaskProfiler`, `ParagraphSegmenter` for every attachment, `RuleBasedProtectedSpanDetector`, and `RecoveryController.standard`. It does not call `LlmTaskProfiler`.
- `PipelineDemo.formatFor` maps formats from file extensions. `.json`, `.csv`, and unknown extensions become `STRUCTURED_DATA`; this is not content-based detection or format-specific parsing.
- `Masker` has unit tests, but no production caller. The budget scorer instead replaces overlapping HARD spans with a generic `" protected "` string for lexical scoring; lossless operations exclude detected spans and the validator re-detects/checks spans.
- The runtime lossless path performs reversible whitespace normalization on prose and exact duplicate removal on prose units. Code, log, and structured attachments are not parsed or summarized.
- `BudgetController` keeps units with HARD spans and essential types, scores the task statement, requirements, and output expectations, then fills budget by fixed type priorities and uncalibrated priors. Constraints are retained as essential but are not included in its ranking query.
- `DependencyGraph` uses regular expressions for Java-like type declarations/references and simple colon lead-ins. It is not a symbol table; it does not parse imports, method calls, inheritance semantics or cross-file language rules.
- `PreservationValidator.standard` registers 11 numbered checks. Deterministic implemented checks are budget, protected spans, exact values, profile-item verbatim carriage, limited structure, regex dependency closure, narrow CSV-row/log-order risk cues, and provenance. Important-fact entailment, semantic verification and reconstruction probe are reported `NOT_RUN`; structure validation does not parse Java, JSON or CSV.
- `RecoveryController` implements accept/revise/fallback mechanics for the current whitespace, exact-dedup and selection operations. Its fallback plans are whitespace-plus-dedup, whitespace-only, and no operator; this is not the full L0–L5 transformation ladder.
- `AnthropicTokenCounter` optionally sends the assembled user message to Anthropic’s token-count endpoint. It is not generation. Without a key, on failure, or when its limited secret-pattern scan matches, it uses an estimate marked `exact=false`.
- `LlmTaskProfiler` and `categorize_agent` are present outside the `PipelineDemo` runtime path. `categorize_agent` sends the full prompt to a configured Kaggle `/generate` endpoint; the profiler’s `CLASSIFICATION_PROMPT` is empty. This path is not consent-gated by `EvaluationSupport.Config` and must not be described as a default-offline safe boundary.
- `EvaluationDemo` uses a local heuristic counter and the real offline optimizer. It now evaluates source-grounded annotations by exact, source-specific carrier matching; invalid annotation offsets/text/source IDs and family leakage fail before a run. It exports per-case and macro/micro TP/FP/FN, precision, recall and F1 rows, and uses `Config.datasetVersion()` in output. Labels remain fixture-authored and unreviewed; current singleton families do not establish generalization. `taskScore` remains `null`; no Java execution or model call occurs. `runOffline` repeats deterministic one-argument arms and does not measure model stochasticity.

## Evaluation and build evidence

The prior recorded full-suite result was `mvn clean test`: **200 tests, 0 failures, 0 skipped**. Fresh validation for the gold-evaluation milestone ran `mvn -Dtest=EvaluationSupportTest test`: **15 passed, 0 failures/errors/skips**, and `mvn test`: **207 passed, 0 failures/errors/skips**. These verify current source on the checked-out dirty worktree. The suite covers unit behavior for ingestion, segmentation, profiling, protection/masking, lossless editing, scoring, budget selection, assembly, validation, recovery, token counting and evaluation-support formulas/export. It does not include downstream model-quality evaluation, Java sandbox validation, or parser-backed structural transformation tests.

The earlier offline smoke run exported 30 case rows for 15 fixtures and two arms. The current exporter adds macro/micro summary rows, but either output demonstrates evaluation plumbing only; it is not evidence that task quality was preserved or improved.

## Research and evidence cautions

The workspace now contains the research blueprint DOCX, `PromptOptimizer — Project Knowledge Base.md`, `PromptOptimizer_Book_Concepts.docx`, and `research paper filter.md`. The book-concepts file summarizes AI Engineering and the knowledge base summarizes selected SLP3 pages; those underlying book pages/source files are not included. The paper filter is a secondary analysis, not the primary papers; its numeric claims and interpretation were not independently verified in this source-only audit. The separately named blueprint PDF was not found.

See [RESEARCH_IMPLEMENTATION_AUDIT.md](./RESEARCH_IMPLEMENTATION_AUDIT.md) for the full research-to-code matrix, status methodology, contribution analysis, and next steps. See [REVISED_ROADMAP.md](./REVISED_ROADMAP.md) for phase sequencing and [REQUIREMENT_TRACEABILITY.md](./REQUIREMENT_TRACEABILITY.md) for stage-level evidence and gaps.
