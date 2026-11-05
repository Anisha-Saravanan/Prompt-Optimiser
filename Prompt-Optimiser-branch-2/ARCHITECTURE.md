# Architecture

## Current maturity

PromptOptimizer is a modular Java 21 research prototype with a local CLI. The runtime is not the full research blueprint: no extractors for structured formats, parser-backed transformations, advanced scorer cascade, downstream model experiment runner, persistence or REST API are present. It favors reversible text operations, optional local selection, deterministic checks and safe fallback over a generic rewrite.

## Implemented command-line path

```text
PipelineDemo
  -> Canonicalizer                         strict UTF-8, size limit, LF normalization, original-byte hash
  -> RuleBasedTaskProfiler                 local labels and imperative-cue fallback
  -> ProfileSegmenter / ParagraphSegmenter source-aligned text units
  -> RuleBasedProtectedSpanDetector        regex-detected spans, all currently HARD
  -> LosslessPass                           reversible prose whitespace + exact duplicate handling
  -> BudgetController                      masked lexical relevance, essential retention, dependency closure
  -> Assembler                             prompt-first, supplied file order, headers, markers, provenance
  -> PreservationValidator                 deterministic checks; unsupported checks reported
  -> RecoveryController                    restore-only retry, fallback ladder, infeasibility result
  -> CLI output
```

`EvaluationDemo` builds separate synthetic prompt/file pairs and invokes the same recovery pipeline with `HeuristicTokenCounter`. It exports original and optimizer-arm token/count/latency rows. It does not call a downstream model or task checker.

## Component audit

| Component | Current implementation | Tests | Status and main gap |
|---|---|---|---|
| Intake/model | `Canonicalizer`, `Model` | `CanonicalizerTest` (6) | PARTIALLY IMPLEMENTED — strict UTF-8, SHA-256, CRLF/CR normalization, NUL and byte limits; no content sniffing, retained raw-byte store or extraction adapters. |
| Format routing | `PipelineDemo.formatFor` | No CLI tests | PARTIALLY IMPLEMENTED — extension-only mapping; unknown formats fall to structured data; no JSON/CSV/Markdown parser. |
| Task profile | `RuleBasedTaskProfiler`, `LlmTaskProfiler`, `TaskProfile` | 5 rule / 6 mocked LLM tests | PARTIALLY IMPLEMENTED — rule path supports labels and a small imperative list; no classification accuracy corpus, conflict detection, task-risk model or `needsFullRows`. Separate Kaggle client can send full prompts externally if invoked. |
| Segmentation | `ProfileSegmenter`, `ParagraphSegmenter`, `Segmenter` | 3 profile / 7 paragraph tests | PARTIALLY IMPLEMENTED — exact partition and fenced-code handling; no AST, CSV-row groups, JSON subtrees or log stack-trace units. |
| Protection | `RuleBasedProtectedSpanDetector`, `Masker` | 8 detector / 6 masker tests | PARTIALLY IMPLEMENTED — regex-only, every detector result is HARD; Masker verifies placeholder order but has no runtime caller. Coverage on representative prompts is unmeasured. |
| Lossless | `LosslessPass`, `WhitespaceNormalizer`, `EditScript`, `LosslessResult` | 32 pass / 16 whitespace / 9 edit / 8 restore tests | PARTIALLY IMPLEMENTED — reversible whitespace and exact prose dedup; no JSON canonicalization, boilerplate removal, CSV/log transforms or near-duplicate merge. Threshold 8 tokens is an uncalibrated placeholder. |
| Scoring | `Bm25JaccardScorer` | 5 tests | PARTIALLY IMPLEMENTED — in-memory lexical BM25 plus set Jaccard and directional overlap features; no TF-IDF cosine, embeddings, learned/calibrated importance or live near-duplicate verifier. |
| Dependencies/budget | `DependencyGraph`, `BudgetController` | 7 budget tests | PARTIALLY IMPLEMENTED — regex type references and lead-ins; type priorities, hard-coded priors and task query are simplified. No Java symbol solver or measured recall@k. |
| Assembly/provenance | `Assembler`, `AssembledInput` | 16 assembler / 3 integration tests | PARTIALLY IMPLEMENTED — stable prompt/file ordering, delimiters, omission markers and output-to-input source ranges; no experimental reordering, task duplication or complete persisted run provenance. |
| Validation | `PreservationValidator`, check classes, `OutputIndex` | 20 validator tests | PARTIALLY IMPLEMENTED — deterministic checks catch tested value/requirement/provenance/structure cases. Check 9 covers only explicit all-row CSV and order-sensitive log cues; important facts, semantic entailment and probe are NOT_RUN; no syntax parsing. |
| Recovery/audit | `RecoveryController`, `RecoveryOutcome`, `OriginalDelivery` | 24 controller / 2 property tests | PARTIALLY IMPLEMENTED — at most three restore rounds, fallback and infeasibility; only current lossless/selection plans exist, no general persisted policy/version audit. |
| Token counting | `TokenCounter`, `HeuristicTokenCounter`, `AnthropicTokenCounter` | 5 heuristic / 4 Anthropic tests | PARTIALLY IMPLEMENTED — heuristic has no target-tokenizer validation; Anthropic endpoint is optional network transfer and counts only the assembled user message. Secret detection is pattern-based. |
| Evaluation | `EvaluationSupport`, `EvaluationDemo` | 8 support tests | PARTIALLY IMPLEMENTED — formulas and CSV structure tested; tiny synthetic set, generic hard-coded carrier counts, no task scores, arms, statistical analysis or cost accounting. |
| API/operations | none | none | NOT IMPLEMENTED — no service, database, auth, retention/deletion, rate limiting, async processing or observability. |

`pom.xml` targets Java 21 and adds JUnit Jupiter only in test scope. There are no JavaParser, Jackson, Commons CSV, PDFBox, Tika, embedding, Spring, or database dependencies.

## S0–S14 alignment

| Stage | Current responsibility | Status |
|---|---|---|
| S0 Intake | `Canonicalizer` and CLI limits; extension-only format declaration | PARTIALLY IMPLEMENTED |
| S1 Extraction/canonical model | Canonical UTF-8 text, LF, original SHA-256 | PARTIALLY IMPLEMENTED |
| S2 Task profiling | Rule profiler is runtime path; LLM profiler is separate opt-in class | PARTIALLY IMPLEMENTED |
| S3 Segmentation | Profile sections and paragraphs | PARTIALLY IMPLEMENTED |
| S4 Protection/dependencies | Regex spans, lexical graph; separate placeholder utility | PARTIALLY IMPLEMENTED |
| S5 Lossless pass | Reversible prose whitespace and exact dedup | PARTIALLY IMPLEMENTED |
| S6 Scoring cascade | Single-stage BM25/Jaccard with fixed priors | PARTIALLY IMPLEMENTED |
| S7 Strategy selection | No versioned task/modality policy registry | NOT IMPLEMENTED |
| S8 Budget allocation | Greedy fixed-priority unit selection with exact recounts | PARTIALLY IMPLEMENTED |
| S9 Controlled optimization | Current lossless pass plus unit dropping; no structural operator cascade | PARTIALLY IMPLEMENTED |
| S10 Assembly | Stable ordering, file headers, markers and provenance | PARTIALLY IMPLEMENTED |
| S11 Validation | Some deterministic checks; others explicitly NOT_RUN | PARTIALLY IMPLEMENTED |
| S12 Decision/recovery | Restore-only retries and current fallback ladder | PARTIALLY IMPLEMENTED |
| S13 Output/audit | CLI output and in-memory transformation/audit records | PARTIALLY IMPLEMENTED |
| S14 Evaluation hook | Optimizer-only synthetic CSV | PARTIALLY IMPLEMENTED |

## Privacy and external boundaries

The standard CLI defaults to rule-based local profiling and a local estimate. If `ANTHROPIC_API_KEY` is configured, the Anthropic counter sends text to a token-count endpoint. Separately, `LlmTaskProfiler` can use `categorize_agent`, which reads Kaggle endpoint credentials from `.env` and posts the full prompt to `/generate`; that path is not wired into the standard CLI and is not protected by the evaluation consent object. The project does not execute Java. Do not claim a sandbox or production access-control boundary exists.

See [SECURITY_AND_PRIVACY.md](./SECURITY_AND_PRIVACY.md) for detailed boundaries and [REVISED_ROADMAP.md](./REVISED_ROADMAP.md) for the proposed next sequence.
