# PROGRESS (recovery file)

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
