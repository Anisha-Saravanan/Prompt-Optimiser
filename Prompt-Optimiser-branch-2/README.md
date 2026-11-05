# PromptOptimizer

PromptOptimizer is a Java 21 research prototype. `PipelineDemo` locally profiles and segments a prompt, then sends the split to a configured Kaggle agent for simplification. The repository also contains later optimization components; this is not a generic LLM rewriter.

The project does **not** establish semantic equivalence or downstream quality preservation. BM25/Jaccard weights, type priors, regex protection, and inferred dependencies are heuristics, not calibrated research findings.

## Run

Requires Java 21+ and Maven:

```powershell
mvn test
mvn package
java -cp target\classes org.promptoptimizer.PipelineDemo --help
java -cp target\classes org.promptoptimizer.PipelineDemo
java -cp target\classes org.promptoptimizer.PipelineDemo --prompt request.txt
```

Fill in `KAGGLE_API_URL` (the base URL, without `/generate`) and `KAGGLE_API_TOKEN` in the ignored project-root `.env` file. `.env.example` shows the required keys. `PipelineDemo` prints and sends grouped JSON with `TASK`, `REQUIREMENTS`, `CONSTRAINTS`, and `OUTPUT FORMAT` fields, then prints the agent's response as JSON. Requirement and constraint values are arrays to avoid repeated JSON keys. The prompt is sent to the configured external service.

Run the seeded, offline optimizer evaluation and export per-case CSV rows:

```powershell
java -cp target\classes org.promptoptimizer.evaluation.EvaluationDemo `
  --output target\evaluation\seeded-java-debug-v1.csv `
  --seed 17 `
  --repeats 1
```

The runner compares original input with the local optimizer. Its task-quality score is blank by design: it makes no model calls and does not execute Java fixtures. The heuristic token counts are explicitly non-exact. See [EVALUATION_GUIDE.md](./EVALUATION_GUIDE.md).

## Runtime and privacy boundary

`PipelineDemo` reads one UTF-8 prompt, uses the local rule-based profiler and segmenter to split it into sentence-level units, then sends the labeled split to the configured Kaggle `/generate` endpoint. Clear sentence cues are typed as requirements, constraints, or output format; ambiguous text remains unknown. Do not use it with sensitive prompts unless the transfer is approved.

The repository also contains a separate `LlmTaskProfiler`/`categorize_agent` path that can send the full prompt to a configured Kaggle `/generate` service. `PipelineDemo` uses the Kaggle client directly with the segmented prompt, not the LLM task profiler. These paths are not protected by the evaluation consent helper; see [SECURITY_AND_PRIVACY.md](./SECURITY_AND_PRIVACY.md).

## Current pipeline

- Strict UTF-8 intake, newline canonicalization, per-document size limits, and extension-based format mapping.
- Local rule-based prompt profiling, sentence-level segmentation with conservative requirement/constraint/output cues, regex protected-span detection, reversible prose cleanup, and exact duplicate handling.
- BM25/Jaccard-style selection, regex-based dependency closure, deterministic validation, restore-only recovery, auditable drops, and fallback/infeasibility outcomes.
- `PipelineDemo` runs prompt profiling and segmentation, sends the labeled split to the Kaggle agent, and prints its response. The later optimization components listed above are not run by this demo.

## Status and roadmap

The project is not a completed implementation of every phase in the supplied blueprint. The evaluation runner has 15 synthetic cases and exports optimizer-only counts; it does not measure task quality. Java AST transforms, gold-label metrics, verified near-duplicate removal, CSV/log transformations, statistical model evaluation, adaptive policies, and production controls remain unfinished. No arbitrary Java is executed; safe execution remains blocked until an OS isolation boundary is independently validated.

See [RESEARCH_IMPLEMENTATION_AUDIT.md](./RESEARCH_IMPLEMENTATION_AUDIT.md) for research-to-code evidence and prioritization. [IMPLEMENTATION_STATUS.md](./IMPLEMENTATION_STATUS.md), [REQUIREMENT_TRACEABILITY.md](./REQUIREMENT_TRACEABILITY.md), and [REVISED_ROADMAP.md](./REVISED_ROADMAP.md) document audited status and sequence. [ARCHITECTURE.md](./ARCHITECTURE.md), [EVALUATION_GUIDE.md](./EVALUATION_GUIDE.md), [SECURITY_AND_PRIVACY.md](./SECURITY_AND_PRIVACY.md), and [PROGRESS.md](./PROGRESS.md) document boundaries, use, and history.
