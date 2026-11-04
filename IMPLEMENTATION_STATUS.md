# PromptOptimizer — Implementation Status

## >>> SESSION 2026-10-17 (milestone-5 continuation) — MILESTONE 4 HANDOFF COMPLETED; MILESTONE 5 NOT STARTED <<<
**Starting state (verified):** the supplied ZIP `promptoptimizer-milestone4-audit-checkpoint.zip` was an EARLIER checkpoint: validator (checks 1,2,3,4,6,10) + 18 tests, but NO `recover` package,
NO `LosslessResult.withRestored`, NO fallback ladder, NO `INFEASIBLE_BUDGET` (grep returned nothing). Baseline re-run: 186 passed / 0 failed on a throwaway stand-in.

**Done this session (Milestone 4 recovery prerequisites):**
- `LosslessResult.withRestored(unitIds, round, reasonCode)` + `state(unitId)` + `isOriginal(UnitState)`; RESTORED records (operator `restore-only-recovery`, level LOSSLESS, round). Restores only from stored reverse information; never adds a transformation; result re-verified.
- `Assembler.header` made public (one visibility change) so the raw original is delivered in exactly the assembler's file-delimiter format.
- New package `org.promptoptimizer.recover` (8 files): `RecoveryController` (S12), `RecoveryPolicy` (max 2 restore rounds; blueprint round 3 needs S8-S9), `CandidatePlan` (+`Ladder`), `RecoveryStatus`
  {ACCEPT, FALLBACK_LESS_LOSSY, FALLBACK_LOSSLESS, FALLBACK_ORIGINAL, INFEASIBLE_BUDGET}, `RecoveryOutcome`, `AuditEntry`, `Infeasibility`, `OriginalDelivery`.
- Tests added: `lossless/RestoreTest` (8), `recover/RecoveryControllerTest` (24), `recover/RecoveryPropertyTest` (2: 300 seeded random inputs/budgets + determinism). Total now 220.
- Mutation spot-checks (scratch copies, not kept): gate off, progress rule off, OVERBUDGET marked restorable, withRestored a no-op, infeasible path off, effective-unit filter off, less-lossy rung skipped, wrong RESTORED action -> each made >=1 test fail. One survivor (budget boundary `>` vs `>=` for the original) exposed a test gap; `theOriginalIsReturnedWhenItJustFitsAndIsInfeasibleWhenItIsOneTokenOver` added and re-checked.

**Recovery behaviour (what the code actually does):**
1. Primary plan (whitespace + exact-dup removal) -> assemble -> validate. ACCEPT iff every mandatory check passes AND tokens <= the original input's tokens (`rejectLargerThanOriginal`, default on; equal is accepted).
2. If it fails and EVERY error is of a restorable class (SPAN_LOST, VALUE_LOST, REQ_MISSING, FACT_LOST, STRUCT_INVALID, DANGLING_REF, SEMANTIC_DRIFT, TASK_RISK) and names a non-original unit: restore those units verbatim (restore-only), re-assemble, re-validate.
   A round must leave the error fingerprints a PROPER SUBSET of the previous round's, otherwise ABORTED (no oscillation). OVERBUDGET, PROVENANCE, findings naming no unit, and "named units already original" go straight to the ladder.
3. Larger-than-original candidates are rejected without restoring (restoring cannot make them smaller) and go to the ladder.
4. Ladder (validate-only, no restore rounds on rungs): FALLBACK_LESS_LOSSY (whitespace only) -> FALLBACK_LOSSLESS (no operators; only file delimiters) -> FALLBACK_ORIGINAL (raw original, records = KEPT per unit, not re-validated, audit lists why every earlier rung failed).
   Because every current operator is reversible, the three rungs are expressed as operators switched off; the ladder is pluggable via `CandidatePlan.Ladder` for when lossy operators exist.
5. If the original exceeds the budget and nothing safe fits: INFEASIBLE_BUDGET, `text == null`, `Infeasibility` {B, M = smallest representation that passed every check except budget, deficit, source, minimumText, piece-wise composition by unit type, the five blueprint options with unavailable ones marked}. Nothing is truncated.
6. Invalid input (duplicate ids, several prompts, foreign profile, negative budget) throws IllegalArgumentException; it is never converted into a fallback. Build/assemble/validate exceptions of a plan ARE recorded in the audit and treated as a rejected rung.
7. An ACCEPT only means the deterministic checks passed; `RecoveryOutcome.warnings` lists every check reported NOT_RUN / NOT_APPLICABLE.

**REAL FINDING (output larger than the original):** under `HeuristicTokenCounter`, removing one exact duplicate inside a file can make the assembled output LARGER than the original (e.g. 95 vs 90 tokens: marker + longer file header cost more than the ~29-token paragraph saves).
This is now handled (rejected -> FALLBACK_LESS_LOSSY). Before this session nothing prevented delivering such an output.

**MILESTONE 5 SCOPE — UNRESOLVED AMBIGUITY (no M5 code written):**
- Neither supplied document uses the word "Milestone". The blueprint roadmap (section 14) has Phases 0-6. "Milestone 1-5" is the numbering of earlier sessions' own plan (see "Remaining milestones" below).
- Earlier plan's item 5 ("BM25 + Jaccard scorer, budget controller, selection with dependency closure; Java AST collapse") corresponds to the REMAINDER of blueprint Phase 2. Blueprint Phase 5 ("Adaptive optimization") is something else and its prerequisite is Phase 4 (evaluation framework: dataset, judges, stats), which does not exist.
- Phase 2 acceptance criteria that cannot be met offline here: unit recall@k on dev labels, false-merge rate on a minimal-pair set, non-inferiority vs original on a pilot (needs Phase 0 harness + LLM + dataset). Java AST collapse needs JavaParser (Maven Central is not reachable).
- Decision needed from the project owner before any M5 implementation. See the final report of this session for the options.

## >>> MILESTONE 4 — scope as selected earlier (superseded by the block above where they differ) <<<
Selected scope (confirmed against blueprint sections 3.1, 4/S11-S13, 6.5, 7.1-7.4, 9, 14 phase 1+2):
- `org.promptoptimizer.validate` (S11): deterministic checks 1 budget, 2 protected spans, 3 exact values, 4 requirement/constraint carriers (deterministic part only),
  6 structure (markers, file headers, non-prose and fenced text untouched), 10 provenance (+ record/hash/token consistency). Checks 5, 8, 11 are model-based: reported NOT_RUN.
  Checks 7 (no dependency graph yet) and 9 (no task-type classification yet) are reported NOT_APPLICABLE with the reason. Nothing claims semantic equivalence.
- `org.promptoptimizer.recover` (S12): restore-only revise (max 2 rounds; blueprint round 3 needs S8-S9 and belongs to Milestone 5), fallback ladder
  FALLBACK_LESS_LOSSY -> FALLBACK_LOSSLESS -> FALLBACK_ORIGINAL, INFEASIBLE_BUDGET, full audit trail.
- Not in scope: S6-S9 (scoring, budget allocator, lossy operators), REST, persistence, LLM verifiers.
Next action when resuming: see the "Exact next action" at the bottom of this file and PROGRESS.md (kept current after every step).


Last updated: 2026-10-17 (Milestone 3 complete; Milestones 1-2 byte-identical to the supplied ZIP; real Maven/JUnit verification still NOT achieved)

## Source of truth
Design baseline: `PromptOptimizer — Research-Driven Pipeline Blueprint` (stages S0–S14, sections 4–7).
Paper/book numbers in the blueprint are second-hand and are not used as requirements. Every
threshold, factor and allowance in code is a placeholder to be calibrated, not a finding.

## Architecture decisions
- Plain Java 21 + Maven. Spring Boot is NOT added yet; it arrives with the REST layer so the core stays framework-independent.
- Package root `org.promptoptimizer`: `model`, `token`, `ingest`, `segment`, `protect`, `task` (Milestone 2), `lossless` and `assemble` (Milestone 3).
- Spans are offset-based records over canonical text. Masking is a separate reversible step (`Masker`), not part of the span model.
- Protection precedes scoring: operators will only see masked text (placeholders `⟦P:n⟧`); `Masker.unmask` rejects missing, duplicated, reordered or altered placeholders.
- `HeuristicTokenCounter` is an upper-bound estimate and always reports `exact=false`. It must never be presented as the target model's tokenizer.
- Ingestion rejects invalid UTF-8, NUL bytes, oversize and blank-id inputs explicitly. Only normalization applied: CRLF/CR to LF (recorded).

## Implemented (Milestone 3: lossless pass, assembly, provenance) — packages `lossless` and `assemble`
Selected from the blueprint: S5 lossless pass, S10 assembly, S13 records/provenance (Phase 1: "lossless ops with reverse maps; assembly"). Matches the
plan left by the previous session. Milestone 1-2 files were not modified (`diff -r` against the supplied ZIP shows only these two docs changed plus new files).

### `org.promptoptimizer.lossless` (S5)
- Inputs: `PreparedDocument(doc, DocRole PROMPT|FILE, ContentFormat, units, spans)`; the constructor re-verifies the partition and that spans belong to the document.
  `ContentFormat` is declared by the caller (no file-type detection exists yet): only PLAIN_TEXT and MARKDOWN are "prose"; SOURCE_CODE / LOG / STRUCTURED_DATA are never edited or deduplicated.
- `TextEdit` (keeps the removed text), `EditScript` (`apply`, `revert` from stored reverse info only, `toTransformed` offset map, `originRange`). Offsets are unit-relative.
- `WhitespaceNormalizer`: removes trailing spaces/tabs and collapses runs of 2+ blank lines to one blank line. Skipped, with a warning, when it would touch a fenced block
  (``` and ~~~, unterminated fences included), a HARD span, or a blank run next to indented text; Markdown keeps two-space hard line breaks.
- `LosslessPass` (+ `Config(normalizeWhitespace, removeExactDuplicates, minDuplicateTokens=8)`): per-unit whitespace edits (rejected if the token count would rise), then exact
  duplicate removal by SHA-256 of the lossless text (trailing whitespace ignored). Survivor = first occurrence, PROMPT documents before FILE documents, so a prompt unit is never
  removed because of a file. A unit is merged only if: its type is not TASK/REQUIREMENT/CONSTRAINT/ERROR_TEXT/EXAMPLE/INPUT_FACT (`NEVER_MERGED`), its token count >= `minDuplicateTokens`,
  no HARD span straddles its boundary, its HARD-span texts are contained in the survivor's, and its omission marker costs strictly fewer tokens than the unit.
- `UnitState` (action, L1 text, reverse map, removed original for merged units, record), `TransformationRecord` (one per unit: action, operator, level, reason code, tokens and SHA-256 before/after, related unit),
  `LosslessResult` (`reconstruct(docId)` rebuilds the original from stored reverse info; `verify()` re-checks reconstruction, one record per unit, and that no edit overlaps a HARD span).
  `LosslessPass.run` calls `verify()` before returning.
- `OmissionMarker`: `[omitted: exact repeat of block N of <docId>]` + blank line (lives here because the pass must know its token cost).

### `org.promptoptimizer.assemble` (S10)
- `Assembler(TokenCounter).assemble(LosslessResult)` -> `AssembledInput(text, tokens, scaffoldTokens, tokensBefore, tokenizerId, segments, records)`.
  Layout: the single prompt first (no header), then each file in the given order under `=== FILE: <id> (<n> lines[; k repeated blocks omitted]) ===`; units in original order;
  removed duplicates appear as visible markers. Baseline policy only: no reordering, no task duplication.
- `ProvenanceSegment` / `Origin` / `AssembledInput.origin(outOffset)`: maps every non-scaffold output character back to original offsets (single char if unchanged, the edited range if produced by an edit,
  the whole removed unit for a marker). `verifyProvenance()` checks segments tile the text and match their reverse maps (run automatically).
- Rejected rather than escaped: more than one PROMPT document, a counter different from the lossless pass's, and any emitted text with a line starting with `=== FILE:` or `[omitted:`.
- Scaffold tokens are counted; `netTokenChange()` can be positive for small inputs (headers cost more than a little cleanup saves). Deciding what to do about that is the validator/recovery milestone.

### Behavioural facts worth knowing
- Under `HeuristicTokenCounter` whitespace costs zero tokens, so whitespace edits save characters but report no token savings. Their value can only be measured with a real tokenizer.
- Exact-duplicate removal is labelled LOSSLESS (as in blueprint 6.1: information is preserved and the original is stored), but a deliberate repeat used for emphasis (blueprint C4) is not preserved as a repeat. Hence the `NEVER_MERGED` types.
- Marker numbering ("block N") is the surviving unit's ordinal+1 in the original paragraph segmentation; the model cannot see that numbering, so it is a hint, not an address.

## Implemented (Milestone 1)
- Enums: `Level`, `SpanKind`, `UnitType`, `Action`.
- Records: `CanonicalDocument`, `InformationUnit`, `ProtectedSpan`, `TokenCount`.
- `Canonicalizer` (S0/S1 subset), `ParagraphSegmenter` + `Segmenter.verifyPartition` (S3 subset, untyped units, fence-aware),
  `RuleBasedProtectedSpanDetector` (S4 subset: constraint sentences, negation/modals, numbers, identifiers, errors/stack frames, paths/URLs, quoted strings), `Masker`.

## Implemented (Milestone 2: task profiling) — package `org.promptoptimizer.task`
Milestone 1 code was not modified. The `task` package depends only on `model.CanonicalDocument`; it does not use the
masker, protected-span detector, segmenter or token counter (kept separate on purpose).

- Domain: `SourceRef` (docId + offsets), `Provenance` (source, rule name, `Basis` = EXPLICIT_LABEL | CUE_RULE | STRUCTURAL_INFERENCE),
  `Confidence` (ordinal LOW/MEDIUM/HIGH, not probabilities), `ItemKind` (TASK, REQUIREMENT, CONSTRAINT, OUTPUT_EXPECTATION),
  `ExtractedItem` (id, kind, label, exact text, provenance, confidence, verbatim-duplicate sources), `ProfileWarning` (code, INFO/WARN, message, source),
  `TaskProfile` (prompt, task, requirements, constraints, outputs, candidates, warnings, confidence, extractorVersion).
- `TaskProfile`'s constructor enforces that every item's text is an exact slice of the prompt, so items cannot be invented.
- `TaskProfiler` interface; `RuleBasedTaskProfiler` (`VERSION = "rules-0.1"`), deterministic, no LLM.

How it works: fenced code and `>` block quotes are set aside; text inside `"..."`, `“...”` and backticks is masked (offsets preserved) so
cue words in it are not read as instructions; labelled sections (`TASK:`, `REQUIREMENTS:`, `CONSTRAINTS:`, `OUTPUT FORMAT:` and synonyms,
plain, bold or markdown-heading form) give HIGH-confidence items; `CONTEXT/BACKGROUND/INPUT/DATA/EXAMPLES/CODE/LOG` sections are skipped.
Unlabelled prose is split into sentences and classified by cues (negation, limit, preservation, obligation, output-format, imperative start).
Labelled list items keep wrapped continuation lines. Exact repeats (after normalization) are merged and the repeat's source is recorded.

Behavioural decisions worth knowing:
- If a labelled section of a kind exists, unlabelled cue sentences of that kind are NOT promoted; they are kept in `candidates`
  (ids X1..) with an `UNPROMOTED_CANDIDATE` warning. This yields the blueprint's 3 constraints while not silently discarding
  "Remember: please don't touch parts of the code that already work...". If no labelled section exists, they are promoted at MEDIUM confidence.
- A sentence can legitimately be both the task and a constraint, or both a constraint and an output expectation ("in no more than 50 words").
- Unlabelled imperative list items / later imperative sentences become LOW-confidence STRUCTURAL_INFERENCE requirements.
- The task is the labelled TASK section (all contiguous lines of it), else the first imperative-looking prose sentence; none -> `MISSING_TASK`.
- Warnings: EMPTY_INPUT, MISSING_TASK, AMBIGUOUS_TASK (<=2 words, pronoun-only object, or vague verb), MULTIPLE_TASK_CANDIDATES,
  EMPTY_SECTION, DUPLICATE_ITEM, UNPROMOTED_CANDIDATE, CUE_IN_CODE_OR_QUOTE.
- Overall confidence: HIGH = labelled task and no WARN warnings; MEDIUM = task found otherwise; LOW = no task / empty.

Acceptance example: blueprint section 10.1 (found in the supplied blueprint, which labels it an invented example, not evidence) is used
verbatim as a test. Result: 1 task, 4 requirements, 3 constraints, 0 outputs, 1 candidate, overall HIGH.

## Implemented (Milestone 2, part 2: profile-aware typed segmentation) — `segment.ProfileAwareSegmenter`
`ProfileAwareSegmenter(Segmenter base, TaskProfile profile) implements Segmenter`. It wraps any base segmenter (normally `ParagraphSegmenter`, unchanged),
keeps its boundaries/ordinals/tokens, changes only `UnitType`, and re-runs `Segmenter.verifyPartition`. `profile == null` returns the base output unchanged;
a profile built from a different document (id or text) is rejected with `IllegalArgumentException`.
Typing rules (conservative; anything else stays UNKNOWN):
- TASK / REQUIREMENT / CONSTRAINT: all overlapping items are EXPLICIT_LABEL or CUE_RULE, lie fully inside the unit, map to one type, and the rest of the unit is only
  scaffolding (whitespace, list markers, recognised labels like `TASK:`). A TASK item containing all other items of the unit types it TASK.
  OUTPUT_EXPECTATION maps to REQUIREMENT (no output type exists in `UnitType`).
- CODE: unit is exactly one terminated fenced block with a language tag. Bare or unterminated fences, fence + trailing prose: UNKNOWN.
- UNKNOWN: mixed kinds, uncovered prose, STRUCTURAL_INFERENCE items, unpromoted candidates (X*), block quotes, quoted text, context sections, greetings, a label-only unit
  separated from its list by a blank line. REPEAT, NOISE, CONTEXT, etc. are never assigned yet.
Blueprint example typing: intro UNKNOWN, task TASK, requirements REQUIREMENT, constraints CONSTRAINT, "Remember..." paragraph UNKNOWN.
Small additive change to the profiler: `RuleBasedTaskProfiler.isSectionLabel(String)` (public static).

Defect found and fixed in the Milestone 2 profiler: when masking a quoted sentence ending in `.`/`!`/`?`, the punctuation was kept visible, creating a false
sentence boundary, so `Translate this sentence: "Do not touch the red button." into French.` lost " into French." from the task. Punctuation is now kept only when the next
word is uppercase/digit or the line ends. Regression test added.

## Files
- Milestone 4 (recovery, this session): main `recover/{RecoveryStatus,RecoveryPolicy,CandidatePlan,AuditEntry,Infeasibility,OriginalDelivery,RecoveryOutcome,RecoveryController}.java` (8) ;
  modified `lossless/LosslessResult.java` (additive), `assemble/Assembler.java` (`header` public). Tests: `lossless/RestoreTest` (8), `recover/{RecoveryControllerTest(24),RecoveryPropertyTest(2)}`.
- Milestone 4 (validator, earlier): `validate/*.java` (17 files), `validate/{ValidationTestSupport,ValidatorTest(18)}`.
- Milestone 3 main: `lossless/{ContentFormat,DocRole,EditKind,TextEdit,EditScript,Hashes,OmissionMarker,TransformationRecord,PreparedDocument,WhitespaceNormalizer,UnitState,LosslessResult,LosslessPass}.java` (13), `assemble/{SegmentKind,AssemblyException,Origin,ProvenanceSegment,AssembledInput,Assembler}.java` (6).
- Milestone 3 tests (76): `lossless/{LosslessTestSupport,EditScriptTest(9),WhitespaceNormalizerTest(16),LosslessPassTest(32)}.java`, `assemble/{AssemblerTest(16),PipelineIntegrationTest(3)}.java`.
- `pom.xml`
- `src/main/java/org/promptoptimizer/{model,token,ingest,segment,protect}/*.java` (16 files)
- `src/main/java/org/promptoptimizer/segment/ProfileAwareSegmenter.java`; test `segment/ProfileAwareSegmenterTest.java` (19 tests)
- `src/main/java/org/promptoptimizer/task/*.java` (9 files: SourceRef, Provenance, Confidence, ItemKind, ExtractedItem, ProfileWarning, TaskProfile, TaskProfiler, RuleBasedTaskProfiler)
- `src/test/java/org/promptoptimizer/{ingest,segment,protect,token}/*Test.java` (5 files, Milestone 1) and `task/RuleBasedTaskProfilerTest.java` (41 tests)

## Verification — what was actually run
### Milestone-5 continuation session (2026-10-17)
- **Real Maven/JUnit: NOT achieved.** `mvn` is not installed in this environment (`mvn: not found`), there is no `~/.m2`, no JUnit jar on the machine, network disabled. No Maven command could be run; nothing was routed around.
  `pom.xml` is unchanged and STILL unconfirmed by a real build. Exact attempt: `which mvn` -> no output; `mvn -v` -> `mvn: not found`.
- Compile: `java -m jdk.compiler/com.sun.tools.javac.Main -source 21 -target 21 -Xlint:all` (`--release 21` fails here: "release version 21 not supported", the JRE has no ct.sym). Only the 3 pre-existing `serial` warnings; none from new code.
- Tests: throwaway stand-in (annotation `@Test` + the `Assertions` subset the tests use + reflection runner) kept OUTSIDE the repo at /home/claude/standin. Observed **220 passed, 0 failed**:
  186 pre-existing (re-run unchanged) + 8 RestoreTest + 24 RecoveryControllerTest + 2 RecoveryPropertyTest. This is NOT equivalent to a Maven/JUnit run; the new tests use only `assertTrue/False/Equals/Null/NotNull/Throws` and `@Test`.
### Milestone 3 session (2026-10-17)
- **Real Maven/JUnit: NOT achieved.** `mvn` 3.9.11 is installed, but `mvn test` could not resolve plugins: first DNS failure for repo.maven.apache.org, then, with the session proxy configured for Maven
  (`-Dmaven.resolver.transport=wagon -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=44023`), HTTP 403. The proxy status endpoint recorded `connect_rejected ... 403 to CONNECT ... repo.maven.apache.org:443`
  (egress policy denial). Per the environment notes this was not retried or routed around. No JUnit 5 jars exist on the machine. `pom.xml` is unchanged and still unconfirmed.
- Compilation: all main sources (46 files incl. the 19 new) compile with `javac --release 21 -Xlint:all`; only three `serial` lint warnings (two pre-existing exception classes, plus `AssemblyException`).
- Tests: compiled and run with a throwaway JUnit stand-in (annotation `@Test` + the `Assertions` subset the tests use + a reflection runner), kept OUTSIDE the repo in the session scratchpad.
  Observed: **168 passed, 0 failed** = 92 Milestone 1-2 (re-run unchanged: 32 M1 + 41 profiler + 19 segmenter) + 76 new Milestone 3.
  This is NOT equivalent to a Maven/JUnit run. The tests only use `@Test` and `Assertions.assertEquals/assertTrue/assertFalse/assertNotEquals/assertThrows/assertNotNull/assertNull`, written to compile against real JUnit 5.10 overloads, but that is unconfirmed.
- Mutation spot-checks (done in a scratch copy, not kept): disabling the excluded-range check, the HARD-span subset rule, the NEVER_MERGED rule, the provenance script mapping, the file separator, and the collision check each made at least one new test fail.
- A seeded randomized property test (300 random prose/Markdown texts with fences, tabs, numbers, quotes, paths, stack-trace text) checks exact reconstruction, no token increase, and that every HARD span of every non-merged unit is present byte-for-byte.
### Earlier session (Milestone 2), kept for history
- Same approach (stand-in runner): 92 passed, 0 failed; real `mvn test` not run (no Maven installed then).

## Known issues / limitations
Milestone 3:
- Formats are caller-declared; there is no S0 intake/extractor, so nothing detects that a ".txt" is really a log. Mislabelling a log as PLAIN_TEXT would let whitespace edits and dedup run on it.
- Dedup works on whole paragraph units only: a repeated sentence inside a paragraph, or a paragraph that differs by one character, is not found. No near-duplicate or paraphrase handling (blueprint conditions 3-4 are not implemented, as planned).
- Thresholds are uncalibrated placeholders: `minDuplicateTokens = 8`, the `NEVER_MERGED` set, the "skip near indented text" rule. The protected-span detector favours recall, so many edits near protected text are skipped; the saving on real prompts is unmeasured.
- Not implemented from S5: JSON re-serialization (needs Jackson or a parser), boilerplate/badge removal, log dedupe. Not implemented from S10: ordering experiments, task-statement duplication flag, dependency/importance order.
- Assembly does not decide whether the result is worth using (it can cost more tokens than the original for small inputs) and does not fall back; that belongs to the validator/recovery milestone. At most one PROMPT document; delimiter/marker collisions are rejected, not escaped.
- `TaskProfile` items and protected spans refer to offsets in the original prompt text, not in the assembled output. `AssembledInput.origin` maps output -> original only; there is no original -> output lookup yet, which the validator will need.
Milestone 2/1 limitations below are unchanged.
Milestone 2 (task profiler):
- English-only lexical cues; no parsing, no LLM. Precision/recall on real prompts is unmeasured. Cue lists and the verb list are small and will miss/over-match.
- `only` is a limit cue, so "I only have 5 minutes" would be read as a constraint (MEDIUM). `keep` alone is not a cue (only "keep ... compatible/unchanged/same/existing/intact").
- Indented (non-fenced) code blocks are not recognised. Single-quoted text is not masked (apostrophes). A labelled TASK section absorbs all contiguous lines, including extra sentences.
- Labelled items are taken as authored: "Return only valid JSON" under REQUIREMENTS stays a requirement (not also an output/constraint item).
- Conflicting instructions are not detected (blueprint S2 step 5); no task-type classification, `needsFullRows`, `riskLevel`, hardness fields or LLM-assisted extraction yet.
- Repeat detection is exact-after-normalization only; paraphrased repeats (blueprint U10/U11) surface as candidates, not as verified duplicates.
- Typed segmentation is conservative: many real units will stay UNKNOWN; its coverage on real prompts is unmeasured. No CONTEXT/NOISE/REPEAT typing, no ERROR_TEXT/DOC/SCHEMA typing.

Milestone 1 (unchanged):
- Detector favours recall: the cue `only`, `no`, and every number, identifier and quoted string are protected, so protected-token fraction may be high. Not yet measured on real prompts.
- No cue-free constraint detection; no AST-based identifier detection; `UnitType` is always `UNKNOWN`.
- Placeholder token cost is not yet budgeted.
- No tokenizer for any real model yet.
- Segmentation is paragraph-only; no format-specific units (Java AST, logs, JSON, CSV).

## Remaining milestones (earlier sessions' numbering; NOT defined by the blueprint)
1-3. DONE: Milestone 1 (ingest/segment/protect/mask/tokens), Milestone 2 (task profile, typed segmentation), Milestone 3 (lossless pass with reverse maps, exact dedup, assembly, provenance map).
4. DONE (this session, stand-in verified only): deterministic validator (checks 1,2,3,4,6,10; 5,7,8,9,11 not run / not applicable), restore-only `RecoveryController`, fallback ladder, `INFEASIBLE_BUDGET`, audit. Checks 7 and 9 still need a dependency graph and task-type classification.
5. BM25 + Jaccard scorer, budget controller, selection with dependency closure; Java AST collapse (needs JavaParser: requires dependency access).
6. Spring Boot REST layer, persistence (PostgreSQL + Flyway), versioning records, S0 intake/extractors.
7. Phase 0 evaluation harness: metrics (TR, RTR, PR, recalls), `LlmClient`, seeded Java items.

## Exact next action
1. Project owner decides the Milestone 5 scope (see the unresolved-ambiguity block at the top). Recommended: the offline-implementable part of blueprint Phase 2 only (BM25 + Jaccard scorer, typed budget controller with the span/closure rules, `DROPPED` handling in recovery incl. blueprint's "drop lowest-density units" OVERBUDGET repair), WITHOUT Java AST collapse (needs JavaParser) and WITHOUT claiming the dev-label acceptance criteria.
2. Run real `mvn test` on a machine with Maven Central access and record the result here (never confirmed in any session). Fix any pom/JUnit issues.
