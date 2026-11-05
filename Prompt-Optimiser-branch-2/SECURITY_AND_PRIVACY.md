# Security and Privacy

## Current data paths

| Path | Default? | Data leaves the local process? | Status |
|---|---|---|---|
| `PipelineDemo` + `RuleBasedTaskProfiler` + `KaggleAgent` | Yes | Sends the segmented prompt to the configured Kaggle `/generate` endpoint | EXPERIMENTAL; requires `.env`; not covered by the evaluation consent gate |
| `AnthropicTokenCounter` | Only if `ANTHROPIC_API_KEY` is present | Sends counted message text to Anthropic’s token-count endpoint | EXPERIMENTAL/optional; not a generation call; pattern secret scan is incomplete |
| `LlmTaskProfiler` + `categorize_agent` | Optional standalone path | Sends the supplied prompt to configured Kaggle `/generate` endpoint | EXPERIMENTAL; not covered by the evaluation consent gate |
| `EvaluationDemo` | Optional local CLI | No model calls; CSV contains item IDs/counts/flags/timing | IMPLEMENTED for optimizer-only export; no task-quality measurement |
| `EvaluationSupport.completeLogged` | No default caller/provider | Depends on injected `LlmClient` | EXPERIMENTAL consent-gated interface/helper, not a provider implementation |

`categorize_agent` reads `KAGGLE_API_URL` and `KAGGLE_API_TOKEN` from `.env`; the URL is the base URL and the client appends `/generate`. `PipelineDemo` passes its segmented prompt along with the optimization instructions to `KaggleAgent`, which uses this client. The wrapper has no explicit consent object or secret scan. Do not use it with sensitive prompts without a separately reviewed consent/data policy.

## Defaults and limits

- The standalone evaluation runner makes no model calls. `PipelineDemo` does send the segmented prompt to Kaggle.
- The default token counter is an explicitly non-exact local heuristic. A configured Anthropic counter sends the assembled user message remotely; surrounding conversation, tools and system prompts are outside its count.
- The likely-secret patterns in `AnthropicTokenCounter` are a narrow filter, not comprehensive secret detection. Hash-keyed in-memory caching stores counts, not prompt strings; hashes are still sensitive metadata.
- The CLI applies an 8 MiB per-document limit and up to 20 attachments. It uses file extensions for format routing; this is not content detection. No PDF/OCR extraction or binary parser is present.
- `CsvUsageLogger` stores model ID, request SHA-256, token counts, latency, cost and status; hashes can leak equality and may be guessed for low-entropy text. Evaluation CSV excludes prompt/answer text.
- The code does not execute supplied or model-generated Java. `TaskChecker` is an interface, not a sandbox.

## Missing controls

The project has no REST server, user authentication, access control, retention/deletion API, rate limiting, asynchronous job limits, structured production observability, comprehensive secret scanner or process/container sandbox. Safe Java execution is **BLOCKED BY EXTERNAL VALIDATION**: a proven boundary must prevent host filesystem and network access and impose CPU, memory, process and time limits. No in-process check or ordinary subprocess should be presented as that boundary.

## Operating guidance

1. Do not run `PipelineDemo` with sensitive data unless sending the segmented prompt to the configured Kaggle endpoint is approved.
2. If exact Claude counting is configured, confirm transfer of the full assembled user message is acceptable.
3. Do not instantiate the Kaggle classifier with private prompts unless its external processing is explicitly approved.
4. Keep `.env`, API keys, usage logs and generated evaluation files out of source control and restrict access.
5. Do not execute arbitrary Java source using this project.

See [ARCHITECTURE.md](./ARCHITECTURE.md) for component boundaries and [REVISED_ROADMAP.md](./REVISED_ROADMAP.md) for security prerequisites.
