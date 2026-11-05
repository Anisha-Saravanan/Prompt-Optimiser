# PromptOptimizer — Revised Research-Driven Roadmap

## 1. Executive summary

PromptOptimizer is a **research prototype / local CLI**, not a production-ready optimizer. It already has useful deterministic foundations: strict UTF-8 intake, local rule-based task profiling, source-aligned text units, regex-based protection, reversible prose cleanup, BM25/Jaccard-style selection, conservative retention, validation, restore-only recovery, a CLI, and an optimizer-only evaluation skeleton. The current implementation does **not** prove that an optimized prompt preserves meaning or produces an equally good answer.

The offline gold-label evaluation foundation is now implemented: source-keyed annotations, exact-carrier metrics, family-split validation and versioned case/aggregate exports exist. The highest-value next step is independent annotation review and expansion into meaningful repeated families. This implementation does not validate optimizer effectiveness. Improve deterministic ingestion and selection only after the label set is suitable; defer advanced methods and downstream-quality claims until then.

The recommended next milestone is **Gold Annotation Review and Dataset Expansion**. No optimizer or compression algorithm change is needed for that work.

## 2. Scope, evidence, and current maturity

### Materials reviewed

The available resources are `PromptOptimizer — Research-Driven Pipeline Blueprint (1).docx`, `PromptOptimizer — Project Knowledge Base.md`, `PromptOptimizer_Book_Concepts.docx`, and `research paper filter.md`. The separately named blueprint PDF, original SLP3 page scans/book, and primary research papers are not present in the workspace. The knowledge base, book-concepts document, and paper filter are secondary summaries; this audit inspects those files but does not claim independent verification of their cited sources or reproduction of paper/book results.

Literature references are idea-level guidance. Numeric claims in secondary notes are **not independently verified** and are not project results; retrieve primary sources and reproduce relevant claims before using them to set product targets.

### Current code/test evidence

- Java 21 Maven project; JUnit is the only declared test dependency. No AST, JSON, CSV, PDF, embedding, or service framework dependency is present.
- Last recorded `mvn clean test` in project history: **200 tests passed, 0 failures/errors**. It was not rerun for this audit because the worktree is dirty and the task prohibits executing fixtures/user code; do not treat it as fresh validation of all current files. The test suite is broad at the unit/property/recovery level but not a CLI integration or task-quality benchmark.
- Standard CLI path uses `RuleBasedTaskProfiler`; format routing is extension-based. The `LlmTaskProfiler`/Kaggle classifier exists separately and can send full prompt text externally.
- The evaluation harness has 15 synthetic cases and fixed 11/2/2 train/dev/test labels, but no family-level leakage control or adequate gold unit/requirement labels. Its task scores are null; no model or Java task execution is performed.

## 3. Product goal and measurement contract

The project goal is **task-aware, evidence-preserving prompt construction under a stated input-token budget**, not paraphrasing for its own sake. It should preserve the user's requirements, constraints, important facts, exact values, output format, relevant evidence and dependencies; transformations should be reversible or independently validated; if safety requirements cannot fit, it should say so instead of silently omitting them.

Keep these outcomes separate:

| Outcome | What it means | Current evidence |
|---|---|---|
| Token reduction | Measured input tokens decrease | Runner has heuristic counts (`exact=false`); no aggregate quality-qualified result |
| Information retention | Gold task-relevant units remain available | No gold-unit annotations or corpus metric |
| Requirement preservation | Required constraints/instructions are carried into the candidate | Validator has limited checks; runner uses two generic phrases and a planted-constraint substring, not gold requirements |
| Downstream task performance | Answer correctness/usefulness after a target model sees the prompt | Unmeasured; `task_score` is null |
| Net cost | Optimizer cost plus downstream input/output cost, latency and cache effects versus baseline | Unmeasured; no downstream usage/cost data |

Compression alone is not success. Report token efficiency only alongside preservation and, eventually, downstream task performance and net cost.

## 4. Research concepts and applicability

Status vocabulary: `IMPLEMENTED`, `PARTIALLY IMPLEMENTED`, `NOT IMPLEMENTED`, `EXPERIMENTAL`, `BLOCKED BY EXTERNAL VALIDATION`.

| Idea/source family | Current relationship | Recommended use and evidence needed | Complexity / cost / principal risk |
|---|---|---|---|
| SLP3 / information retrieval: term weighting, BM25, query-document ranking | PARTIALLY IMPLEMENTED | Keep BM25 as an interpretable lexical baseline; tune only against gold relevance labels and train/dev partitions; report recall@k and budget curves | Low CPU/API cost; lexical mismatch, query omissions, arbitrary weights |
| Jaccard / overlap features | PARTIALLY IMPLEMENTED | Retain as a cheap feature, not a semantic verifier; test polarity, units, negation, and reordered facts | Low cost; token overlap can equate contradictory meanings |
| Chip Huyen / evaluation-driven development, observability and cost | PARTIALLY IMPLEMENTED | Make offline labels and paired evaluation precede advanced methods; record stage latency, tokenizer certainty, API usage and net cost | Moderate dataset effort; judge/data leakage can make results misleading |
| Sentence-BERT / embedding similarity | NOT IMPLEMENTED; optional candidate experiment | Use only to nominate near-duplicate candidates; require an exact/semantic verifier and human-labeled hard negatives | Medium/high model footprint or API cost; anisotropy, numbers and negation can cause false merges |
| LLMLingua / LongLLMLingua token pruning | NOT IMPLEMENTED; EXPERIMENTAL | Isolate as a later matched-budget arm after baseline/task checks; never make it the default transform without paired non-inferiority evidence | High integration/GPU or API cost; token-level pruning can damage syntax, exact values and low-frequency facts |
| Reverse-likelihood / perplexity pruning | NOT IMPLEMENTED; EXPERIMENTAL | Consider only if tokenizer/log-probability access, cost, and matched-budget tests justify it | High model/compute dependence; fluency is not task importance |
| Lost in the Middle / position sensitivity | NOT IMPLEMENTED as policy | Add a position sweep to evaluation before enabling reorder/duplication; keep stable order in default CLI | Moderate experiment effort; target-model and context-length dependence |
| RepoBench / repository context retrieval | PARTIALLY IMPLEMENTED by intent only | Replace regex Java dependency guesses with parser-backed symbols/imports and curated dependency recall/false-positive tests | Moderate/high parser work; incomplete projects and dynamic/reflection edges |
| SWE-bench-style task checking | NOT IMPLEMENTED | Use only safe, objectively checkable fixtures first; Java execution is BLOCKED BY EXTERNAL VALIDATION pending OS sandbox proof | High operational/security burden; flaky tests and unsafe execution |
| Self-RAG / LLM categorical labeling | Separate LlmTaskProfiler exists; no standard-path integration | If ever tested, use only borderline units, explicit consent, minimal data, injection-resistant instructions, and human agreement study | High external cost/privacy risk; prompt injection, nondeterminism, unsupported labels |
| DeepSeek-OCR / visual document extraction | NOT IMPLEMENTED; not MVP | Defer until OCR/PDF is a real requirement and extraction fidelity can be measured | High model/runtime and data handling cost; layout and OCR errors |
| Structured parsing and reversible transformations | NOT IMPLEMENTED for JSON/CSV/AST | Prioritize parser-backed Java and structured-data support; preserve exact source and parse/round-trip | Medium/high engineering; parse failures and semantic loss |

No technique is accepted because it appears in a paper. Every nontrivial transform or scorer must beat the simpler baseline at matched budget without violating predeclared preservation/task-quality constraints.

## 5. Current implementation audit

| Area | What works today | Gap / consequence | Status |
|---|---|---|---|
| Intake/format | Strict UTF-8, LF normalization, per-file size limit; extension-to-format mapping | No sniffing or format parser. Unknown extensions map to structured data; do not claim TXT-to-Markdown conversion/extraction support beyond assembled output | PARTIALLY IMPLEMENTED |
| Task profiling | Runtime rules identify explicit labels and a narrow imperative cue | No gold accuracy, risk/ambiguity model, or `needsFullRows`; separate Kaggle path is not consent-gated | PARTIALLY IMPLEMENTED |
| Segmentation | Prompt sections and paragraphs preserve source slices | Java remains paragraph-sized; no methods/classes/import graph, CSV row group, JSON subtree or log event units | PARTIALLY IMPLEMENTED |
| Protection | Regex detector returns HARD spans; checks protect selected exact values | Coverage/precision unknown; no soft-span system; `Masker` utility is tested but has no runtime caller | PARTIALLY IMPLEMENTED |
| Lossless operations | Reversible prose whitespace cleanup and exact whole-unit duplicate removal | No format-aware transform, near-dedup, proof of semantic equivalence or calibrated minimum benefit | PARTIALLY IMPLEMENTED |
| Scoring/selection | Custom BM25/Jaccard, fixed type priors, conservative essential types, exact assembled recount | No calibration, constraints omitted from ranking query, regex dependency closure can be wrong, no measured recall@k | PARTIALLY IMPLEMENTED |
| Validation | 11 checks registered; deterministic checks cover selected values, spans, requirements, markers, closure and provenance | Important-fact, semantic consistency and reconstruction probe are NOT_RUN; no syntax parsing; CSV/log risk check is narrow | PARTIALLY IMPLEMENTED |
| Recovery | Up to three restore rounds and whitespace/dedup/no-op fallback plans | No general AST/token-pruning operator ladder; no proof for strategies not implemented | PARTIALLY IMPLEMENTED |
| Token counting | Local heuristic or optional Anthropic count endpoint; errors/non-exactness are surfaced | Heuristic uncalibrated; Anthropic sends assembled prompt; pattern secret scan is incomplete | PARTIALLY IMPLEMENTED |
| Evaluation | 15 synthetic cases with source-ID/offset labels, exact-carrier TP/FP/FN, per-case and macro/micro CSV, family-split checks and configured version propagation | Labels are fixture-authored/unreviewed, families are singletons, negative coverage is sparse; no task checker/model, bootstrap, quality curves, matched arms or net cost; repeats are deterministic | PARTIALLY IMPLEMENTED |
| API/operations | Local CLI only | No service, auth, persistence, retention/deletion, rate limits, job controls or production monitoring | NOT IMPLEMENTED |

`PipelineDemo` uses the local rule profiler, `ParagraphSegmenter` for attachments, `RuleBasedProtectedSpanDetector`, and `RecoveryController.standard`. `AnthropicTokenCounter` optionally sends the assembled user message to Anthropic. The separate `LlmTaskProfiler`/`categorize_agent` can send full prompt text to Kaggle `/generate`; it is not wired into the CLI and not governed by the evaluation consent configuration.

## 6. S0–S14 to official Phases 0–6

| Blueprint stage | Current coverage | Official phase(s) | Main missing acceptance |
|---|---|---|---|
| S0 Intake | PARTIALLY IMPLEMENTED | 1, 6 | Content/encoding validation contract, manifest, unsupported-file behavior tests |
| S1 Extraction/canonical model | PARTIALLY IMPLEMENTED | 1 | Format-specific extractors and round-trip fidelity |
| S2 Task profiling | PARTIALLY IMPLEMENTED | 1, 2 | Labeled profile-quality and ambiguity/risk behavior |
| S3 Segmentation | PARTIALLY IMPLEMENTED | 1, 2 | AST/JSON/CSV/log units with exact coverage |
| S4 Protection/dependencies | PARTIALLY IMPLEMENTED | 1, 2 | Runtime masking, labeled precision/recall, AST dependency closure |
| S5 Lossless pass | PARTIALLY IMPLEMENTED | 1 | Structural transforms and format-specific round-trip validation |
| S6 Scoring | PARTIALLY IMPLEMENTED | 2, 3 | Gold labels, calibrated baseline, semantic candidate/verifier experiments |
| S7 Strategy registry | NOT IMPLEMENTED | 2, 5 | Versioned task/format/risk policies |
| S8 Budget allocation | PARTIALLY IMPLEMENTED | 2 | Calibrated utility and preservation/performance-versus-budget curves |
| S9 Optimization operators | PARTIALLY IMPLEMENTED | 1–3 | AST, CSV/log/JSON and optional experimental pruning |
| S10 Assembly | PARTIALLY IMPLEMENTED | 1, 4 | Position experiments; keep default order deterministic |
| S11 Validation | PARTIALLY IMPLEMENTED | 1, 2 | Implement/measure missing checks and parser-backed structure |
| S12 Recovery | PARTIALLY IMPLEMENTED | 1, 2 | Operator-specific rollback and tested strategies |
| S13 Output/audit | PARTIALLY IMPLEMENTED | 1, 6 | Complete versioned audit, storage/retention policy |
| S14 Evaluation hook | PARTIALLY IMPLEMENTED | 0, 4 | Gold metrics, real checker/arms, statistical evidence and costs |

The original blueprint's Phase 0 acceptance is broader than today's skeleton. Phases 0 and 4 are related but different: Phase 0 establishes reproducible data/checker/runner contracts; Phase 4 measures paired downstream quality, statistics and economics. Phase 3 techniques should not precede a reliable Phase 0/1 baseline. Phase 5 requires enough non-leaking Phase 4 evidence. Phase 6 service work is not a prerequisite for the local CLI research result.

## 7. Ranked feature backlog (A–O)

Rank reflects value and evidence dependency, not implementation convenience.

| Rank | Feature | Status / priority | Complexity and cost | Main failure mode | Acceptance/evaluation |
|---|---|---|---|---|---|
| A | Gold-labeled offline preservation dataset and honest metrics | PARTIALLY IMPLEMENTED → P0 next | Medium; manual annotation; no API | Labels fail to cover paraphrases/edge cases; split leakage | Every case has stable IDs/spans; all metrics reconcile with hand-calculated fixtures; family-disjoint splits |
| B | Correct experiment configuration, version, split and budget output | PARTIALLY IMPLEMENTED → P0 | Low/medium; local | Reported config does not match executed dataset/arm; duplicate repeats misread as stochastic | Configured dataset version used; deterministic repeats explicitly identified; rows reproducible |
| C | CLI integration and malformed-input regression suite | NOT IMPLEMENTED → P0 | Medium; local | Unit success hides argument/path/output errors | End-to-end temporary-directory tests for success, failure, budget and no-write-on-failure |
| D | Content-aware text/format intake and explicit unsupported types | PARTIALLY IMPLEMENTED → P1 | Medium; local | Misclassification or silent lossy fallback | Golden tests for TXT, Markdown, Java, JSON, CSV, log, unknown/binary; fail explicitly where unsupported |
| E | Gold-tested profile/constraint extraction and task-risk policy | PARTIALLY IMPLEMENTED → P1 | Medium; local | Missing/false requirements, ambiguous cues | Human-labeled profile corpus; per-kind precision/recall and conservative ambiguity fallback |
| F | Parser-backed Java segmentation and dependency graph | PARTIALLY IMPLEMENTED → P1 | High; parser dependency | Parse errors, incomplete build context, reflection/dynamic references | AST exact source spans; curated imports/calls/inheritance fixtures; no dangling required symbol links |
| G | Calibrated typed unit scoring/budget allocation | PARTIALLY IMPLEMENTED → P1 | Medium; local | Overfit priors or omitted rare-but-critical unit | Train/dev only; held-out recall/constraint preservation versus tokens across budgets |
| H | CSV row/schema-aware and log event/stack transforms | NOT IMPLEMENTED → P2 | Medium/high; parser dependency | Broken row order/schema or lost stack causality | Golden round-trip tests; row/column and event-order invariants; full-row tasks retain required rows |
| I | Verified near-duplicate removal | NOT IMPLEMENTED → P2 | Medium; local candidate generation | False merge on negation, units, exception details | Labeled minimal pairs; any protected/contradictory candidate must remain separate |
| J | Runtime placeholder masking and labeled protected-span policy | PARTIALLY IMPLEMENTED → P1 | Medium; local | Masking hides the query or detector misses secret/value | Integrate utility only with reversible source mapping; span recall and exact-value tests |
| K | Embedding/semantic candidate generation | NOT IMPLEMENTED, EXPERIMENTAL → P3 | Medium/high; local model or API | Semantic false positives, model drift, sensitive data transfer | Candidate recall measured; verifier false-merge gate; opt-in and matched-budget ablation |
| L | Token-level pruning (LLMLingua family) or reverse-likelihood | NOT IMPLEMENTED, EXPERIMENTAL → P3 | High; model/GPU/API cost | Damages syntax, exact values or low-frequency facts | Separate arm; no default path; paired task score, preservation and net-cost comparison |
| M | Paired downstream task-quality evaluation | NOT IMPLEMENTED → P2 after A/B | High; consented model/human/checker effort | Judge bias, stochasticity, unsafe execution, weak statistical power | Multiple target models, matched budget, paired intervals, noise floor and human/checker validation |
| N | Interpretable adaptive policy by task/format/risk | NOT IMPLEMENTED → P4 | Medium/high; needs Phase 4 data | Policy overfit and category shift | Freeze dev-selected policy; held-out generalization and confidence intervals |
| O | Production service, persistence, and Java sandbox | NOT IMPLEMENTED / sandbox BLOCKED BY EXTERNAL VALIDATION → P5 | High; infrastructure/security | Data leakage, unsafe execution, abuse, retention failures | Threat model, external isolation validation, auth/retention/rate-limit/security tests before launch |

### Feature selection principles

- Deliver A and B before claiming requirement or compression-quality results.
- D–J improve deterministic behavior and can proceed without external models; prefer focused vertical increments over adding a dependency for every format.
- M is not “LLM-as-judge by default.” Prefer objective checkers and blinded human evaluation where feasible. Any provider call requires explicit consent, data minimization, and separate cost tracking.
- K/L are experiments, not core requirements; add only if matched-budget evidence shows value not delivered by simpler retrieval/structure methods.
- O is not needed to complete the local research CLI. Safe arbitrary Java execution remains blocked until an OS-level boundary is independently demonstrated.

## 8. Revised phase plan

### R0 — Offline gold-label preservation evaluation (implemented foundation)

- **Official alignment:** Phase 0 foundation and the measurement prerequisites of Phase 4; closes no downstream-quality claim.
- **Goal:** Replace fixed phrase counts with a source-validated exact-carrier annotation contract for requirements, constraints, exact values/protected spans and selected information units.
- **Implemented files:** Existing `EvaluationSupport.java`, `EvaluationDemo.java`, and `EvaluationSupportTest.java`; updated `EVALUATION_GUIDE.md`, `REQUIREMENT_TRACEABILITY.md`, `IMPLEMENTATION_STATUS.md`, `PROGRESS.md`, and this roadmap. No dependency or new Java file was added.
- **Implemented behavior:** Annotation source IDs/offsets/text and provenance; invalid annotation and duplicate-ID reporting; family-split validation; exact matching in the annotated source's assembled region; per-case TP/FP/FN/precision/recall/F1; macro case means and micro pooled counts; configured version propagation; task score remains null.
- **Validation limitation:** Current synthetic labels record fixture intent and are not independently human-reviewed. Most are positive-only; unannotated extra candidate text is not an FP. The 15 current family IDs are singletons and cannot support family generalization. Exact matching is not semantic matching.
- **Definition of done:** The implementation and focused tests exist; it is an evaluation plumbing foundation, not evidence of optimizer effectiveness, answer quality or net savings.

### R0.1 — Gold annotation review and dataset expansion (recommended next)

- **Goal:** Independently review and adjudicate current source spans, add meaningful repeated task families, and annotate relevant and explicitly non-relevant units so precision and family-held-out evaluation have useful denominators.
- **Scope:** Dataset annotations and evaluation documentation/tests only; no compression changes, model calls, dependency changes or Java execution.
- **Acceptance:** Provenance identifies reviewers and adjudication, source offsets still validate, cases are partitioned by family before any tuning, and test families remain untouched. Report dataset size and coverage by family/type; do not make effectiveness claims from the current 15 singleton-family fixtures.
- **After R0.1:** Run the matched-budget lexical ablations recommended by [RESEARCH_TECHNIQUE_WISE_USE_REVIEW.md](./RESEARCH_TECHNIQUE_WISE_USE_REVIEW.md), then proceed to R1/R2 only if evidence identifies a concrete reliability or selection gap.

### R1 — Reliable deterministic intake and preservation baseline

- **Official alignment:** Complete core Phase 1; foundational S0–S5/S10–S13.
- **Goal:** Make supported input behavior explicit and testable before broadening transformations.
- **Likely files:** `PipelineDemo.java`, `Canonicalizer.java`, `Model.java`, `Segmenter` implementations, `RuleBasedTaskProfiler.java`, CLI tests; format extractor classes only when a format is accepted.
- **Prerequisite:** R0 labels provide regression cases; no model calls.
- **Sequence:** Add CLI integration tests; establish strict supported-format list and explicit unsupported-format error; handle malformed UTF-8, oversized files, duplicate paths and output failures; retain source offsets/hashes; document text-only extraction and output format separately.
- **Tests/acceptance:** CLI writes only after successful validated result; original bytes/hash and required carriers remain traceable; every accepted type has golden cases; unsupported types fail explicitly, never silently map to prose.
- **Risk/DoD:** Avoid adding extractors whose fidelity is not tested. Phase completes only for explicitly supported formats, not for every extension.

### R2 — Measured information-aware selection and task policy

- **Official alignment:** Phase 2; S2/S4/S6–S8/S11/S12.
- **Goal:** Improve typed selection and dependency closure based on labels, not guessed weights.
- **Likely files:** `RuleBasedTaskProfiler.java`, `TaskProfile.java`, `DependencyGraph.java`, `Bm25JaccardScorer.java`, `BudgetController.java`, validator tests, policy/metrics code.
- **Prerequisite:** R0 gold labels; R1 deterministic input contracts. Basic work can be staged in separate PRs.
- **Sequence:** Add labeled task-profile errors; include constraints in query; define unit importance/retention; compare BM25/Jaccard and simple rules on train/dev; build parser-backed dependencies for Java after choosing/validating a parser; introduce explicit risk-driven no-op/full-context policies.
- **Tests/acceptance:** Held-out requirement/protected-unit recall reported per budget; no dangling dependency in curated fixtures; hard content never silently dropped; no claimed calibration from test-set tuning.
- **Risk/DoD:** Lexical similarity misses semantically important details. Keep ranking as candidate evidence, not proof; show false positives/negatives and preserve fallback behavior.

### R3 — Parser-backed, reversible structural transformations

- **Official alignment:** Phase 2's missing structural work; S1/S3/S4/S9/S11.
- **Goal:** Transform only formats whose structure and round trip can be checked.
- **Likely files:** Add/modify `Segmenter`, `DependencyGraph`, `LosslessPass`, `EditScript`, `PreservationValidator`; introduce parser dependency only after a small feasibility spike.
- **Prerequisite:** R0 label corpus and R1 format contract; R2 selection API. Java AST is the first candidate; JSON/CSV/log follow separate evidence.
- **Sequence:** Start with Java declarations/method summaries and exact symbol/import dependencies; retain signatures, annotations, errors and exact values; parse candidate and compare required structures. Add JSON/CSV/log only as separate adapters with schema/order invariants.
- **Tests/acceptance:** Golden source mapping and parse/round-trip; malformed input fails safe; task-critical structures and protected spans retained; no transform applied if parse confidence is insufficient.
- **Risk/DoD:** AST simplification can hide build context or change behavior. No code execution is needed for syntax-preservation tests, and AST output is not treated as behaviorally equivalent.

### R4 — Downstream task-quality and net-cost evaluation

- **Official alignment:** Complete Phase 4; S14.
- **Goal:** Determine whether token savings preserve answer utility and reduce total cost.
- **Likely files:** `EvaluationSupport.java`, `EvaluationDemo.java`, explicit `TaskChecker` implementations, evaluation data/config/docs; provider adapter only if separately approved.
- **Prerequisites:** R0 valid labels and R1/R2 stable optimizer; explicit data/model consent. Safe Java execution is not assumed.
- **Sequence:** Use objective non-executing checkers where possible; otherwise human rubric. Compare original, closed-book, safe lossless, BM25, random-drop, and optimized arms at matched budgets; pair by item and model; record model/tokenizer/version, prompt/answer usage, time and price; pre-register noise floor, non-inferiority margin and analyses.
- **Tests/acceptance:** Reproducible result rows; paired confidence intervals; quality vs budget; net cost includes optimizer overhead; separate model results; test set untouched until freeze.
- **Risk/DoD:** Judge bias, model variance and insufficient power. Do not use self-judging as sole correctness; do not execute arbitrary Java. If no consent, retain offline results and mark downstream quality unmeasured.

### R5 — Advanced strategy experiments

- **Official alignment:** Phase 3; S6/S7/S9.
- **Goal:** Test embedding candidate generation, optional LLM classification, token pruning or reverse-likelihood only if simpler baselines leave a measured gap.
- **Prerequisite:** R0–R4 measurement path; privacy and cost approvals; matched-budget protocols.
- **Sequence:** One isolated arm at a time; candidate generation cannot perform destructive merging; build human-labeled hard-negative set; run paired ablations and report provider/GPU cost and failure cases.
- **Acceptance:** Improvement beyond noise floor with no predeclared preservation/task-quality regression; otherwise do not ship.
- **Risk/DoD:** Privacy exposure, injection, nondeterminism and integration complexity. All are EXPERIMENTAL and off by default until evidence and opt-in are reviewed.

### R6 — Adaptive, interpretable policy

- **Official alignment:** Phase 5; S7/S8.
- **Goal:** Choose safe strategies by task, file type, risk and budget using frozen, interpretable rules.
- **Prerequisite:** Adequate Phase 4 labeled results across categories and budgets.
- **Sequence:** Tune only on train/dev, freeze policy/version, evaluate on family-disjoint test; compare with one global policy and always-safe baseline; support no-op on uncertainty.
- **Acceptance:** Held-out benefit with confidence intervals and no critical subgroup regression; policy version appears in audit.
- **Risk/DoD:** Small samples and category drift. If sample power is inadequate, retain static policy and report NOT IMPLEMENTED.

### R7 — Production hardening (only if deployment is requested)

- **Official alignment:** Phase 6; S0/S13 and operational envelope.
- **Goal:** Add a service only after the offline contract and data policy are stable.
- **Prerequisite:** Threat model, explicit deployment requirement, data retention decision, external model consent policy.
- **Sequence:** Authentication/authorization, rate and size limits, secure logging, retention/deletion, dependency updates, observability and incident handling. Any Java execution sandbox is a separate externally validated deliverable.
- **Acceptance:** Security/privacy review, operational tests, deletion proof and abuse limits. Arbitrary code execution remains BLOCKED BY EXTERNAL VALIDATION until OS-level filesystem/network isolation and CPU/memory/time controls are independently demonstrated.
- **Risk/DoD:** Expands attack surface and sensitive-data exposure. Do not add a REST server merely to call the project “complete.”

### Parallel work and ordering

R0 is the selected immediate milestone. R1 CLI tests and documentation can begin in parallel after the baseline is recorded; format extractors should wait for a supported-format decision. R2's gold-label work depends on R0, while parser feasibility can be explored separately without integrating a lossy transform. R4 protocol design can be documented early, but results require valid labels, a stable optimizer, consent and appropriate checkers. R5 and R6 are gated on R4 evidence. R7 is optional, not on the critical path to a useful local CLI.

## 9. Priority list: next ten implementation tasks

1. Add stable source-grounded gold labels for current synthetic cases (R0).
2. Replace generic fixed phrase and planted substring counts with ID/span metrics.
3. Validate label offsets/text and family-disjoint data splits.
4. Fix configured dataset-version propagation and truthfully label deterministic repeats.
5. Add hand-computed metric and export-reconciliation tests in existing evaluation test file.
6. Add CLI integration tests for output, budget failure, unsupported input and no-write-on-failure.
7. Define accepted format/extension behavior and explicit unsupported-input errors.
8. Add labeled task/constraint profiling and risk behavior with conservative ambiguity fallback.
9. Calibrate selection and dependency closure on train/dev, reporting held-out preservation-versus-budget.
10. Prototype Java AST segmentation/dependencies with syntax/round-trip tests before enabling structural compression.

## 10. Blockers and non-goals

- **Source verification:** The three requested research-note resources are now present and included in [RESEARCH_IMPLEMENTATION_AUDIT.md](./RESEARCH_IMPLEMENTATION_AUDIT.md). Their underlying primary literature/source scans remain unverified.
- **No task-quality evidence:** Formula support and a CSV export are not performance evaluation.
- **No exact local token count:** Heuristic fallback is `exact=false`; an exact provider count transfers the assembled text.
- **No production privacy boundary:** Kaggle classifier exists outside standard CLI; require explicit data review before use.
- **No safe Java sandbox:** Execution remains BLOCKED BY EXTERNAL VALIDATION.
- **No silent format conversion:** Extension mapping and `.md` output are not a TXT/Java/PDF parser or semantic conversion feature.
- **Avoid file sprawl:** Keep evaluation schema/labels in the existing evaluation classes first; add format adapters only when needed and tested.

## 12. Research-resource audit update

The previously stated absence of the Project Knowledge Base, Book Concepts, and paper filter was a workspace-availability finding from an earlier audit and is superseded: those files are now present and inspected. R0 now provides an offline exact-carrier evaluation foundation, but annotation review, meaningful repeated families, downstream answer quality and net-cost measurement remain incomplete. The [RESEARCH_IMPLEMENTATION_AUDIT.md](./RESEARCH_IMPLEMENTATION_AUDIT.md) distinguishes BM25 from TF-IDF, fixed-priority budget selection from task-adaptive budgets, and secondary paper analysis from verified primary-source evidence. The [RESEARCH_TECHNIQUE_WISE_USE_REVIEW.md](./RESEARCH_TECHNIQUE_WISE_USE_REVIEW.md) recommends measuring lexical ablations before advanced methods.

## 11. Recommended next Copilot prompt

> Complete only R0.1, Gold Annotation Review and Dataset Expansion, from `REVISED_ROADMAP.md`. Review each current source-grounded annotation with a domain reviewer, document provenance/adjudication without claiming review until it has occurred, and expand cases into repeated meaningful task families with explicit relevant and non-relevant information-unit labels. Freeze family-disjoint splits before tuning. Do not change compression behavior, call models/APIs, execute Java or introduce dependencies. Extend annotation-validation tests and documentation; report label coverage and remaining evidence gaps. Do not claim optimizer effectiveness.
>
