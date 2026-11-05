# Requirement Traceability

The blueprint has S0–S14 pipeline stages and official Phases 0–6. `IMPLEMENTED` is reserved for behavior that exists in the runtime path and has relevant tests. `PARTIALLY IMPLEMENTED` identifies a tested subset, not completion of the whole requirement. `EXPERIMENTAL` is optional/non-default and not a production guarantee.

## Pipeline-stage matrix

| Stage | Official phase | Intended functionality | Current implementation and tests | Remaining work / dependencies | Priority | Status |
|---|---|---|---|---|---|---|
| S0 Intake | 1 | Validate request/files; identify type, encoding, hash, limits | `Canonicalizer` strict UTF-8, NUL/size rejection, LF normalization and byte hash; 6 ingestion tests | Content sniffing, manifest, archive/path protections, typed errors at CLI boundary; prerequisites for format adapters | P0 | PARTIALLY IMPLEMENTED |
| S1 Extraction/canonical model | 1 | Per-format extraction, structure, offsets and confidence | Canonical text and source offsets for text only | TXT/Markdown/Java/JSON/CSV/log extraction adapters, PDF later, parse-error fallback, round-trip fidelity | P0 | PARTIALLY IMPLEMENTED |
| S2 Task profiling | 1–2 | Task type, statement, requirements, constraints, output, risk | Local label/imperative rules in runtime; separate LLM parser; 5 and 6 profiler tests | Gold-labeled accuracy, conflicts, task types, needsFullRows/risk; external classifier is not a default | P0 | PARTIALLY IMPLEMENTED |
| S3 Segmentation | 1–2 | Format-aware, exact-partition information units | Profile and paragraph segmentation; 10 focused tests | AST methods, CSV row groups, JSON subtrees, log entries/stacks, Markdown headings | P1 | PARTIALLY IMPLEMENTED |
| S4 Protected spans/dependencies | 1–2 | Hard/soft spans, exact values, dependency graph before scoring | Regex detector, tested Masker utility, regex type/lead-in graph | Runtime placeholder masking integration, soft-span policy, precision/recall corpus, AST/symbol dependencies | P0 | PARTIALLY IMPLEMENTED |
| S5 Lossless pass | 1 | Safe cleanup, exact dedup, reverse maps | Reversible prose whitespace/exact duplicate behavior; 65 combined pass/edit/whitespace/restore tests | Format-specific structural lossless passes, more round-trip golden tests | P0 | PARTIALLY IMPLEMENTED |
| S6 Scoring cascade | 2–3 | Cheap rules/BM25, TF-IDF/Jaccard, then optional calibrated semantic candidates | BM25/Jaccard and pairwise directional overlap; 5 scorer tests | No TF-IDF/cosine, embeddings, learned weights, gold unit relevance or calibration | P1 | PARTIALLY IMPLEMENTED |
| S7 Strategy selection | 2, 5 | Versioned policy by task, format, risk, budget and cost | No policy registry | Rule table after labeled data; policy versions and risk/no-op behavior | P2 | NOT IMPLEMENTED |
| S8 Budget allocation | 2 | Minimum-safe representation, typed allowance, closure, exact count | Greedy fixed-priority selection and exact recount; 7 budget tests | Calibrate value/token utility; task constraints in query; score curves; robust exact tokenizer handling | P1 | PARTIALLY IMPLEMENTED |
| S9 Controlled optimization | 1–3 | Separate dedup, transformations, selection and optional pruning | Lossless pass and unit dropping; recovery audit | Java AST, CSV/log/JSON transformations; verified near-dedup; token pruning only in experimental isolated arm | P1 | PARTIALLY IMPLEMENTED |
| S10 Assembly | 1 | Stable ordering, delimiters, visible omissions, token count and provenance | Prompt-first, file order, counted scaffolding, source ranges; 19 tests | Optional order/position experiments, message structures, richer file manifest; do not reorder by default | P1 | PARTIALLY IMPLEMENTED |
| S11 Validation | 1–2 | Eleven checks across budget, spans, values, requirements, structure, closure, risk, provenance, semantics and probe | Eight deterministic checks run, with task-risk limited; 20 validator tests | Important-fact/semantic/probe checks remain NOT_RUN; Java/JSON/CSV parsing absent; check effectiveness not corpus measured | P0 | PARTIALLY IMPLEMENTED |
| S12 Decision/recovery | 1–2 | Accept/revise by restoring, safe fallback, explicit infeasibility | Up to three restore-only rounds; whitespace/dedup/no-op ladder; 26 recovery tests | General operator-level rollback, persisted policy versions, more failure-class mutation tests | P0 | PARTIALLY IMPLEMENTED |
| S13 Output/audit | 1, 6 | Output, terminal operation record, hashes, tokenizer/policy, warnings | In-memory per-unit records, hashes, warnings and CLI output | Persisted versioned run/audit, retention controls, complete policy/tokenizer metadata | P1 | PARTIALLY IMPLEMENTED |
| S14 Evaluation hook | 0, 4 | Run arms/models/checkers/repeats; calculate metrics and net cost | 15 fixture cases with source-ID/offset annotations, exact-carrier TP/FP/FN, per-case rows, macro/micro summaries, family split checks, configured version propagation; focused evaluation tests | Annotations are not independently reviewed; categories are mostly positive-only; families are singletons; no task checker, downstream calls, inferential statistics or cost accounting | P0 | PARTIALLY IMPLEMENTED |

## Official phase traceability

| Phase | Intended completion evidence | Current evidence | Dependencies and gap | Status |
|---|---|---|---|---|
| 0 Evaluation skeleton | 15–25 cases, gold checks, consented client, repeated baseline, checker and reproducible export | 15 synthetic fixtures, source-validated annotations, exact-carrier metrics, family-disjoint split validation, configured versions, per-case and macro/micro CSV | Fixture labels are not independently reviewed; families are singletons; no checker, sandbox, downstream outputs or inferential claims; split sizes remain 11/2/2 | PARTIALLY IMPLEMENTED |
| 1 Reliable baseline | Safe intake/extraction, deterministic task/profile, protected spans, lossless pass, assembly, validation, fallback, audit | Text pipeline works; full `mvn test` currently passes 207 tests | Extension-only format mapping; no structural parsers, CLI tests or broad malformed-format test corpus | PARTIALLY IMPLEMENTED |
| 2 Information-aware | Typed units, Java dependencies, BM25, verified dedup, typed budget, structural Java/CSV/log operators | BM25/Jaccard, lexical closure, exact dedup, conservative budget selection, narrow check 9 | No AST/symbol graph, verified near-duplicates or format-specific transforms; no preregistered recall target | PARTIALLY IMPLEMENTED |
| 3 Advanced | Optional embedding/LLM/sidecar strategy with matched-budget ablations | No corresponding scorer/operator/provider implementation | Must wait for offline baseline, data/privacy design and consent | NOT IMPLEMENTED |
| 4 Evaluation | Human-verified labels, models/arms, statistical reporting, curves and costs | Metric formulas and optimizer-only CSV schema | No task-quality labels/scores, multiple models, bootstrap, NIA, costs or confidence intervals | PARTIALLY IMPLEMENTED |
| 5 Adaptive | Dev-tuned interpretable policy with frozen held-out evaluation | No calibrated policies | Requires sufficient labeled Phase 4 data and category generalization | NOT IMPLEMENTED |
| 6 Production | Auth, retention/deletion, limits, monitoring, rate limits, secure sandbox where needed | Local CLI limits; no service | Service threat model and validated isolation are prerequisites | PARTIALLY IMPLEMENTED |

## Evaluation metric availability

| Metric | Can current code calculate it? | Is it measured by `EvaluationDemo`? | Limitation |
|---|---|---|---|
| TR, RTR, CR | Yes, in `Metrics.calculate` given token counts | Raw counts only; no aggregate metric report | Heuristic tokenizer is `exact=false` |
| Gold annotation precision/recall/F1 | Source-offset annotation and metric implementation exist | Yes: exact, source-specific carrier matches with TP/FP/FN per case/type and macro/micro summaries | Current labels are fixture-authored/unreviewed; false-positive precision is limited to explicit negative labels; unannotated extra text is not an FP |
| Protected-span exact preservation | `PROTECTED_SPAN` gold type uses exact text matching in the annotated source region | Yes, per case/type and aggregate | Evaluates only supplied annotations, not regex detector coverage or semantic equivalence |
| Information-unit preservation | `INFORMATION_UNIT` annotations are supported and measured | Yes, exact-span only | Small fixture inventory; no unit relevance benchmark or paraphrase-aware matcher |
| Downstream task performance, PR, ONS | Formula slots exist for numeric scores | No; `taskScore` is null | No provider run or concrete TaskChecker |
| Structure validity | Narrow header/marker/fenced/non-prose checks | Not exported as metric | No Java/JSON/CSV parser |
| Optimizer latency | Per-arm elapsed milliseconds | Yes, coarse wall time | No per-stage p50/p95 breakdown |
| Optimizer/provider cost, total cost, net savings | Usage metadata fields can hold cost | No actual costs or cost comparison | No downstream calls, prices, caching, compute amortization |
| Confidence intervals, NIA/equivalence, multiple budgets | No analysis implementation | No | Requires dataset, repeats, pre-registration and statistical analysis |

## Research-resource availability

Available in the project folder: `PromptOptimizer — Research-Driven Pipeline Blueprint (1).docx`, `PromptOptimizer — Project Knowledge Base.md`, `PromptOptimizer_Book_Concepts.docx`, and `research paper filter.md`. The named blueprint PDF and original SLP3 page scans/book and primary papers are not available in the workspace. The knowledge base and research filter are secondary summaries; their research numbers are not project measurements and were not independently verified against primary sources.

See [RESEARCH_IMPLEMENTATION_AUDIT.md](./RESEARCH_IMPLEMENTATION_AUDIT.md) for concept-by-concept evidence and status. See [REVISED_ROADMAP.md](./REVISED_ROADMAP.md) for the feature backlog and acceptance plan.
