# PromptOptimizer

PromptOptimizer is a Java 21 research prototype. `PipelineDemo` sends each prompt to `SegmentAgent`, which categorizes it as a task, requirements, and constraints, then sends that split to a configured Kaggle agent for simplification. The repository also contains later optimization components; this is not a generic LLM rewriter.

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

Fill in `KAGGLE_API_URL` (the base URL, without `/generate`) and `KAGGLE_API_TOKEN` in the ignored project-root `.env` file. `.env.example` shows the required keys. `PipelineDemo` sends the full prompt to `SegmentAgent`, which returns JSON with mutually exclusive `TASK`, `REQUIREMENTS`, and `CONSTRAINTS` categories; each detail should appear once, and output and formatting instructions belong under requirements. It sends that result to the optimizer, then runs up to five prompt-checker feedback iterations. Each turn prints the raw agent JSON and, when corrections are returned, its parsed reasoning summary and corrected prompt. The checker returns exactly `{}` when no corrections are needed, which stops the loop early; the final corrected prompt is printed afterward. The checker returns a concise rationale summary, not private step-by-step reasoning. The prompt is sent to the configured external service.

Launch the JavaFX chat interface with:

```powershell
mvn javafx:run
```

The chat UI sends each submitted prompt through `PipelineDemo.optimizePrompt`, which runs the segmentation agent, Kaggle optimization, and feedback-checking workflow. Only the final optimized prompt is added as an assistant message.

Run the seeded, offline optimizer evaluation and export per-case CSV rows:

```powershell
java -cp target\classes org.promptoptimizer.evaluation.EvaluationDemo `
  --output target\evaluation\seeded-java-debug-v1.csv `
  --seed 17 `
  --repeats 1
```

The runner compares original input with the local optimizer. Its task-quality score is blank by design: it makes no model calls and does not execute Java fixtures. The heuristic token counts are explicitly non-exact. See [EVALUATION_GUIDE.md](./EVALUATION_GUIDE.md).

## Runtime and privacy boundary

`PipelineDemo` reads one UTF-8 prompt and sends it to the configured Kaggle `/generate` endpoint for segmentation, optimization, and preservation checking. The agent categorizes the prompt as task, requirements, and constraints. Do not use it with sensitive prompts unless the transfer is approved.

The repository also contains a separate `LlmTaskProfiler`/`categorize_agent` path that can send the full prompt to a configured Kaggle `/generate` service. `PipelineDemo` uses the Kaggle client directly with the segmented prompt, not the LLM task profiler. These paths are not protected by the evaluation consent helper; see [SECURITY_AND_PRIVACY.md](./SECURITY_AND_PRIVACY.md).

## Current pipeline

- Strict UTF-8 intake, newline canonicalization, per-document size limits, and extension-based format mapping.
- Agent-based prompt segmentation into task, requirements, and constraints; the local rule-based profiler and segmenter remain available to other pipeline components.
- BM25/Jaccard-style selection, regex-based dependency closure, deterministic validation, restore-only recovery, auditable drops, and fallback/infeasibility outcomes.
- `PipelineDemo` uses `SegmentAgent` to categorize prompts into task, requirements, and constraints, then runs Kaggle optimization and preservation checking. The later optimization components listed above are not run by this demo.

## Status and roadmap

The project is not a completed implementation of every phase in the supplied blueprint. The evaluation runner has 15 synthetic cases and exports optimizer-only counts; it does not measure task quality. Java AST transforms, gold-label metrics, verified near-duplicate removal, CSV/log transformations, statistical model evaluation, adaptive policies, and production controls remain unfinished. No arbitrary Java is executed; safe execution remains blocked until an OS isolation boundary is independently validated.

See [RESEARCH_IMPLEMENTATION_AUDIT.md](./RESEARCH_IMPLEMENTATION_AUDIT.md) for research-to-code evidence and prioritization. [IMPLEMENTATION_STATUS.md](./IMPLEMENTATION_STATUS.md), [REQUIREMENT_TRACEABILITY.md](./REQUIREMENT_TRACEABILITY.md), and [REVISED_ROADMAP.md](./REVISED_ROADMAP.md) document audited status and sequence. [ARCHITECTURE.md](./ARCHITECTURE.md), [EVALUATION_GUIDE.md](./EVALUATION_GUIDE.md), [SECURITY_AND_PRIVACY.md](./SECURITY_AND_PRIVACY.md), and [PROGRESS.md](./PROGRESS.md) document boundaries, use, and history.
