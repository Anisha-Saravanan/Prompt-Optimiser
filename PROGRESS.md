# PROGRESS (recovery file)

## SESSION 2026-11-04 — OFFLINE GOLD-LABEL PRESERVATION EVALUATION

Extended the existing evaluation model with source-ID/offset gold annotations for fixture task information, requirements, constraints, selected exact values, and protected spans. Annotation provenance explicitly says the seed labels are not independently human-reviewed. Dataset validation reports unknown sources, invalid offsets, source-text mismatches, duplicate IDs, and family leakage; invalid datasets are rejected before evaluation.

Replaced fixed phrase/constraint substring counts with exact, case-sensitive carrier checks restricted to each annotation's corresponding prompt/file region. Per-case annotation-type rows include TP/FP/FN, precision, recall and F1; CSV exports add macro case means and micro pooled-count summaries. Configured dataset versions now propagate. `taskScore` remains null; deterministic repeats are not treated as stochastic trials.

Added focused tests for valid/invalid annotations, hand-calculated metrics, undefined empty denominators, source-specific protected spans, family leakage, configured version, repeat reproducibility, and CSV/count agreement. Focused command `mvn -Dtest=EvaluationSupportTest test`: **15 tests passed, 0 failures/errors/skips**. Full `mvn test`: **207 tests passed, 0 failures/errors/skips**. The 15 fixture families are singletons and annotations remain unreviewed/mostly positive-only; this is evaluation plumbing, not evidence of optimizer effectiveness.

## SESSION 2026-11-04 — PHASE 0 EVALUATION SKELETON
Added `EvaluationSupport` with reproducible 15-item synthetic Java fixtures and fixed train/dev/test labels, TR/RTR/CR, requirement/constraint precision-recall-Fβ, PR, ONS and OE formulas, explicit undefined values at zero/noise-floor denominators, opt-in provider-neutral client configuration, and privacy-conscious usage/result CSV exports. Added a repeated optimizer-arm runner that receives seed/repeat values and leaves downstream task scores empty.
Added `EvaluationDemo`, which runs the existing offline optimizer over all 15 fixtures and writes per-case results without making model calls or executing Java. The heuristic counts are marked non-exact. Added 8 focused tests for dataset shape/reproducibility, metrics, no-score behavior, repeat seeding, consent gating, and content-free CSV output.
Added `REQUIREMENT_TRACEABILITY.md`, `ARCHITECTURE.md`, `EVALUATION_GUIDE.md`, and `SECURITY_AND_PRIVACY.md`; refreshed README and current phase statuses. Clearly marked Java execution/sandbox, downstream task evaluation, statistical comparisons, Java AST transforms, advanced strategies, adaptation and production controls as incomplete or blocked.
Verification: `mvn clean test` passed **200 tests, 0 failures**. `EvaluationDemo` exported 30 rows (15 cases × original/pipeline arms); `PipelineDemo --help` succeeded. The synthetic rows do not measure model quality. Arbitrary Java execution is intentionally not implemented because no supported OS isolation boundary is established.

## SESSION 2026-11-04 — OFFLINE CLI AND CLAUDE TOKEN COUNTING
Added `RuleBasedTaskProfiler` so the runnable CLI classifies only explicit section labels or a narrow imperative cue; it makes no external model calls. Reworked `PipelineDemo` into an offline-first CLI that reads a UTF-8 prompt plus up to 20 attachments, classifies common source/log/document formats conservatively, preserves attachment text, and writes the assembled prompt to stdout or a requested file. Supports a direct input-token budget or a context-window budget minus reserved output tokens.
Added `AnthropicTokenCounter` for the official Claude Sonnet 5.5 token-count endpoint. API use requires `ANTHROPIC_API_KEY`; likely credential patterns are blocked from provider counting and all unavailable/blocked counts fall back with `exact=false`. The CLI clearly warns that estimated counts cannot guarantee a hard Claude limit. Budget ranking now includes output-format requirements.
Added deterministic check 9 for explicit full-row CSV and order-sensitive log tasks; omitted units are identified for restore-only recovery. Added tests for local profiling, exact/non-exact token-counter behavior, API failure, local-only operation, secret blocking, and check 9. Focused validation/recovery tests passed (51 tests); final full real `mvn test`: 192 passed, 0 failed.
Offline CLI smoke test produced a Markdown-assembled prompt with an attachment header. Remaining blueprint phases are not complete: Phase 0 has no seeded model-evaluation corpus/runner; Phase 2 still lacks Java AST transforms, verified near-duplicates and broader task-risk policy; Phases 3–5 require external model evaluation or labeled data and remain disabled/not started; Phase 6 lacks production service controls. See current phase table in IMPLEMENTATION_STATUS.md.

## SESSION 2026-11-04 — PHASE 2 CORE
Aligned the handoff with the blueprint's Phases 0–6 (the numbered milestones were local session bookkeeping). Implemented the selected offline Phase 2 scope: deterministic BM25/Jaccard features, masked task-plus-requirement queries, conservative typed budget selection with exact assembled recounts, dependency closure and validator check 7, auditable/recoverable `DROPPED` units, and restore-time budget reselection.
Added tests for ranking, pairwise overlap, Java type-reference closure, dangling-reference validation, exact budget selection, protected-unit retention, drop restoration, and a fresh-detector failure that requires reselection.
Verification: full real `mvn test` passed (181 tests, 0 failures; Java 25 runtime, release target 21). Heuristic scores and type priors remain uncalibrated; no recall@k, false-merge, or model-quality claims are made. Java AST transforms, automatic near-dedup, task-risk check 9, Phase 0 evaluation and Phases 3–6 remain future work.

## SESSION 2026-10-22 — LLM TASK PROFILING AND SEGMENTATION
Replaced rule-based prompt profiling with `LlmTaskProfiler`, which calls the existing Kaggle-backed `categorize_agent` and adapts structured `TASK`, `REQUIREMENT`, `CONSTRAINT`, and `OUTPUT_EXPECTATION` items into the existing `TaskProfile` contract. Classified text must be an exact, ordered slice of the canonical prompt; source offsets and existing metadata are retained.
Added `ProfileSegmenter` to split prompt text at classified item boundaries while retaining paragraph segmentation for unclassified text and file inputs. Existing `TaskProfile`, `ExtractedItem`, `InformationUnit`, and pipeline interfaces remain in place. `CLASSIFICATION_PROMPT` is an empty editable placeholder. API errors, malformed JSON, unknown types, missing fields, and non-source-exact item text fail explicitly.
Removed `RuleBasedTaskProfiler.java` and `ProfileAwareSegmenter.java`; removed their obsolete tests. `ParagraphSegmenter.java` remains used for file and fallback segmentation. Updated `PipelineDemo` and test fixtures to use the new flow; added LLM profiler and profile segmenter tests.
Verification: all 166 tests passed; all production sources compiled using `javac --release 21`; no source references remain to the removed profiler or segmenter. Maven tests were not run because `mvn` is unavailable. Live Kaggle response shape remains unverified; the adapter currently expects a direct JSON object with an `items` array.

## SESSION 2026-10-17 (milestone-5 continuation) — READ FIRST
Step A DONE: verified the supplied ZIP = earlier M4 checkpoint (validator only; no recovery). Baseline 186/186 on stand-in (not real JUnit).
Step B DONE: Milestone 4 recovery implemented: `LosslessResult.withRestored`, package `recover` (8 files), 34 new tests. Stand-in: 220 passed / 0 failed. NOT real Maven/JUnit (mvn not installed here).
Step C DONE: Milestone 5 scope analysis. RESULT: ambiguous (blueprint has Phases 0-6 and no "Milestone"; earlier numbering != blueprint phases). NO M5 code written. Decision needed - see IMPLEMENTATION_STATUS.md top block.
Checkpoint ZIP: `promptoptimizer-milestone4-complete.zip`. Stand-in runner (outside repo): /home/claude/standin/build.sh <project-dir> [class-filter].
Not verified: real Maven build; real JUnit semantics; behaviour with a real tokenizer (all token numbers are heuristic upper bounds).

## AUDIT RESULT (2026-10-17, audit session) — READ FIRST
The supplied checkpoint ZIP contains the full Milestone 1-3 project plus `org.promptoptimizer.validate` (checks 1,2,3,4,6,10) and `ValidatorTest` (18 tests).
It does NOT contain `LosslessResult.withRestored`, any `org.promptoptimizer.recover` package, the fallback ladder, `INFEASIBLE_BUDGET`, or recovery audit output.
`grep -r "FALLBACK_\|INFEASIBLE_BUDGET\|withRestored\|RecoveryController" src` returns nothing. The previous session's own "Step 2 NEXT" confirms it.
=> Milestone 4 was NOT complete. Baseline re-run in this audit: 186 passed / 0 failed on a throwaway JUnit stand-in (not real JUnit). `mvn` is not installed here and Maven Central DNS fails.
Audit work in progress: implementing `LosslessResult.withRestored` + package `recover` + tests. If this file still says IN PROGRESS, the work below is unfinished.

## MILESTONE 4 IN PROGRESS (2026-10-17)
Step 0 done: scope selected (see IMPLEMENTATION_STATUS.md top).
Step 1 done: `org.promptoptimizer.validate` (checks 1,2,3,4,6,10 implemented; 5,7,8,9,11 reported NOT_RUN/NOT_APPLICABLE) + `ValidatorTest` (18 tests). Stand-in run: 186 passed (168 old + 18 new), 0 failed. NOT a real Maven/JUnit run.
Step 2 NEXT: `LosslessResult.withRestored`, then package `recover` (Policy, RecoveryController, audit, fallback ladder, INFEASIBLE_BUDGET) and its tests. Checkpoint ZIP: `promptoptimizer-milestone4-checkpoint.zip`.
Environment facts this session: `mvn` is NOT installed (`mvn -v` fails), no `javac` binary but the `jdk.compiler` module exists (compile with `java -m jdk.compiler/com.sun.tools.javac.Main`), no JUnit jar on the machine, network disabled.
Next: build baseline with a throwaway JUnit stand-in kept OUTSIDE the repo, then implement `validate`, then `recover`.

Latest checkpoint ZIP: `promptoptimizer-milestone3.zip` (earlier: `promptoptimizer-milestone2-complete.zip`)

## Completed
- Milestone 1 (ingest, paragraph segmentation, protected spans, masking, heuristic tokens). Unchanged.
- Milestone 2 (task profiling; profile-aware typed segmentation). Unchanged.
- Milestone 3 (2026-10-17): `org.promptoptimizer.lossless` (EditScript reverse maps, WhitespaceNormalizer, LosslessPass with exact dedup, TransformationRecord, LosslessResult.verify)
  and `org.promptoptimizer.assemble` (Assembler, ProvenanceSegment/Origin, AssembledInput). Details in IMPLEMENTATION_STATUS.md.

## Files
- Added main: 13 files in `lossless/`, 6 in `assemble/`. Added tests: 5 files (76 tests). Modified: `IMPLEMENTATION_STATUS.md`, `PROGRESS.md` only.

## Tests actually run
- Throwaway JUnit stand-in + reflection runner (outside the repo): 168 passed, 0 failed (92 M1-M2 unchanged + 76 new).
- Real `mvn test`: NOT run successfully. Maven 3.9.11 exists but Maven Central is denied by the egress proxy (403 on CONNECT to repo.maven.apache.org:443). Not bypassed.

## Known limitations
- Maven/JUnit build unverified. Caller-declared formats; paragraph-level exact dedup only; placeholder thresholds; no validator/fallback yet. See IMPLEMENTATION_STATUS.md.

## Remaining
- Run real `mvn test`. Then Milestone 4: deterministic validator + restore-only recovery + fallback ladder.

## File consolidation (77 -> 32 main source files)
Small enums, records, interfaces and exceptions are now nested `public static` types inside a related host file. Class names, behaviour and tests are unchanged; only import paths changed (e.g. `org.promptoptimizer.model.Action` is now `org.promptoptimizer.model.Model.Action`).

| Host file | Types nested inside it |
|---|---|
| `model/Model` | Action, Level, SpanKind, UnitType, CanonicalDocument, InformationUnit, ProtectedSpan |
| `token/TokenCounter` | TokenCount |
| `task/TaskProfile` | Confidence, ItemKind, ExtractedItem, ProfileWarning, Provenance, SourceRef, TaskProfiler, TaskProfilingException |
| `lossless/EditScript` | EditKind, TextEdit |
| `lossless/LosslessResult` | TransformationRecord, UnitState, PreparedDocument, DocRole, ContentFormat, Hashes, OmissionMarker |
| `assemble/AssembledInput` | AssemblyException, Origin, ProvenanceSegment, SegmentKind |
| `ingest/Canonicalizer` | InputRejectedException |
| `recover/RecoveryOutcome` | AuditEntry, Infeasibility, RecoveryStatus, RecoveryPolicy, CandidatePlan |
| `validate/ValidationResult` | Check, CheckId, CheckResult, FailureClass, Finding, ValidationContext |
| `validate/PreservationValidator` | BudgetCheck, ExactValueCheck, ProtectedSpanCheck, UnsupportedCheck |

Also: `CanonicalizerDemo` merged into `SegmenterDemo`; stale `categorize_agent.class` removed from `src/`.

## SESSION 2026-11-04 — RESEARCH-TO-IMPLEMENTATION AUDIT
Inspected the newly available `PromptOptimizer — Project Knowledge Base.md`, `PromptOptimizer_Book_Concepts.docx`, and `research paper filter.md`, alongside the blueprint, current docs, evaluation source/tests, scorer, budget controller and associated pipeline components. Corrected stale statements that these three research notes were missing. The primary papers and original SLP3 pages are still not in the workspace; secondary numeric claims were not independently verified.
Added `RESEARCH_IMPLEMENTATION_AUDIT.md` with 48 deduplicated concept clusters, code/test evidence, exact status vocabulary, wise-use decisions, contribution hypotheses, risks, prioritized milestones and a ready next-step prompt. Corrected `IMPLEMENTATION_STATUS.md`, `REQUIREMENT_TRACEABILITY.md`, and `REVISED_ROADMAP.md`.
Confirmed Gold-Label Preservation Evaluation is not complete: no family/gold-span labels, generic requirement phrase and planted-constraint substring counts remain, configured dataset version is ignored when creating rows, and current CLI repeats deterministic one-argument arms. Confirmed BM25 IDF is not TF-IDF and budget selection is greedy with fixed priorities/priors, not a learned task-adaptive policy.
Audit-only restrictions observed: no Java/dependency changes, no tests/fixtures executed, no network or external model calls. The enclosing worktree had 257 tracked project paths marked modified/deleted at inspection; those changes were preserved. Prior recorded 200-test result is historical and was not revalidated against this dirty source state.

## SESSION 2026-11-04 — SOURCE-VERIFIED AUDIT AND REVISED ROADMAP
Audited the available research blueprint, implementation, tests, CLI, privacy paths, and evaluation harness before proposing more code. Only `PromptOptimizer — Research-Driven Pipeline Blueprint (1).docx` was found in the workspace; the separately named PDF and three research digest files were not present for independent inspection. The DOCX contains a secondary synthesis; its research figures are not project measurements or independently reproduced evidence.
Reconciled `README.md`, `ARCHITECTURE.md`, `IMPLEMENTATION_STATUS.md`, `REQUIREMENT_TRACEABILITY.md`, `EVALUATION_GUIDE.md`, and `SECURITY_AND_PRIVACY.md` with the actual source. Added `REVISED_ROADMAP.md` with the S0–S14/Phase 0–6 mapping, evidence caveats, ranked backlog, phased acceptance criteria, and one selected next milestone. No Java source, dependencies, or runtime behavior changed.
Key corrections: the standard CLI uses local rule profiling and extension-only format mapping; the separate Kaggle classifier can send full prompts externally and is not covered by the evaluation consent gate; `Masker` has no runtime caller; dependency inference is regex-based; structural file parsers are absent; several validator checks are NOT_RUN; current evaluation's generic substring counts are not gold-label metrics and repeat runs are deterministic, not model trials.
Verification for the audited baseline: `mvn clean test` passed 200 tests, 0 failures/errors. Documentation changes are subject to targeted Markdown-link and `git diff --check` validation; no model calls or user-code execution were performed.
