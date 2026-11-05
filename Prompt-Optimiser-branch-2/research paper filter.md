```
Papers vs. PromptOptimizer: relevance analysis
```

```
Throughout, Demonstrated means the paper actually shows it. Proposed means it is
my extension and untested.
```

```
Paper 1: DeepSeek-OCR (optical context compression)
```

```
1. Core contribution. It shows that a rendered page of text can be decoded from
far fewer vision tokens than text tokens. Decoding precision is about 97% below
roughly 10× compression and about 60% at 20×. The authors frame it as a path to
long-context compression and "forgetting" for multi-turn history.
```

```
2. Techniques and metrics
```

```
Compression ratio defined as text tokens ÷ vision tokens, reported as a curve
(Table 2).
Resolution modes (Tiny 64 → Gundam-M) used as a compression dial.
Normalized edit distance split by text, formula, table and reading order
(OmniDocBench).
Per-document-type results (Table 4).
Charts converted to HTML tables as the label format, chosen because it saves
tokens (Fig. 6).
```

# `3. Directly usable` 

```
Evaluation design for the file-transformation stage. Edit distance split by
text/formula/table/order is a ready-made structural-preservation metric for PDF
→ Markdown conversion.
```

```
A PDF/scanned-document parser in the File Analyzer, if you accept a Python model
service beside the Spring Boot app. This is a tool-level reuse, not a research
idea.
Chart → HTML table as a compact representation for charts in PDFs.
```

# `4. Adaptable` 

```
Table 4 supports your hypothesis that the right strategy depends on input type.
Slides need 64 tokens, while newspapers need Gundam mode. The ratio-vs-fidelity
curve per input type is the template for your own experiments.
```

```
Progressive degradation by age (Fig. 13) could apply to conversation history if
your scope includes chat context. This is a proposed extension.
```

```
Optical rendering is a candidate arm in your strategy comparison, but only for
multimodal target models (proposed).
```

# `5. Irrelevant` 

```
SAM/CLIP/MoE architecture, the training pipeline, data engine, GPU throughput,
multilingual and chemistry/geometry parsing.
```

# `6. Limitations and gaps` 

```
It measures reconstruction, not task success. The authors say OCR alone is
insufficient and list needle-in-a-haystack and downstream tests as future work.
Your central question (task performance retention) is exactly what it doesn't
answer.
```

```
It is not lossless. At <10× there is still roughly 3% error. That is tolerable
for prose, but one wrong digit in a constraint or error message can change a
task. No code or exact-string tests exist.
```

```
The test set is small and easy. It uses 100 English pages with 600–1,300 tokens.
The result is non-monotonic. For financial reports, Gundam scores 0.289 edit
distance versus 0.022 for Large (Table 4). Bigger budgets aren't reliably
better, and the paper doesn't explain why.
```

```
It requires a vision-capable consumer. DeepSeek-OCR itself isn't a chatbot.
Whether a general VLM reads rendered text as well, and how vision tokens are
priced, is not shown.
```

```
Paper 2: RepoBench (repository-level code completion benchmark)
```

```
1. Core contribution. It is a benchmark that separates retrieval (R), completion
(C) and the end-to-end pipeline (P) for cross-file code context. Python and
Java, with post-cutoff test repositories to limit contamination.
```

```
2. Techniques and metrics
```

```
acc@k for retrieval, and Exact Match / Edit Similarity for generation.
Settings that control whether the needed information appears for the first time:
XF-F, XF-R and IF, where IF is a robustness check that extra context doesn't
hurt.
```

```
Easy/hard splits by candidate count.
```

```
Retrieval baselines: random, Jaccard, edit similarity, CodeBERT, UniXcoder.
Context ablations and placement tests (gold snippet at head vs. tail).
```

# `3. Directly usable` 

```
RepoBench-P as a stress test for the code category. It has large Java and Python
inputs (mean about 44k and 139k tokens) and automatic ground truth. Compress the
cross-file context, then compare EM/ES against the uncompressed baseline.
acc@k against gold snippets is an objective test of your importance estimator
for code units. Does the estimator rank the gold snippet near the top?
Retrieval baselines for Strategy 1 vs 2. Demonstrated: Jaccard beat edit
similarity, and UniXcoder was best. A cheap lexical scorer is a credible
baseline before embeddings.
```

```
Dataset construction practices: tree-sitter parsing, difficulty strata, a post-
cutoff test set and a "does it hurt?" control setting.
```

# `4. Adaptable` 

```
Query focus. Retrieval was best when using only the last 3–5 lines as the query,
and degraded with 120 lines (Appendix B). Proposed analogue: use the task
statement, not the whole prompt, as the importance query. Note that RepoBench's
query is code, not natural language, so the transfer is unproven.
```

```
Import statements as compact, high-value context. Adding them lifted Python XF-F
EM from 7.30 to 22.28 (Table 5). Demonstrated: cheap structural information can
be very valuable per token. Proposed: signature/import "skeletons" as a code
representation. RepoBench does not test skeletons, only full snippets.
Placement effects. Order mattered in the paper, so your optimizer's output
ordering should be an experimental variable.
```

# `5. Irrelevant` 

```
Codex/CodeGen/StarCoder comparisons, fine-tuning runs, quantization details, and
next-line prediction as a task.
```

# `6. Limitations and gaps` 

```
Wrong task type. Next-line completion is not debugging, explanation or
refactoring, and EM/ES don't work for those outputs.
```

```
Retrieval gains are modest. In Table 4, Python goes from 33.15 baseline to 37.11
for the best retriever, and Java from 40.40 to 41.83. Random context alone
helped slightly.
```

```
Model-dependent. StarCoder often did best with no retrieval (Table 10), and its
performance dipped at medium lengths. Context helpfulness depends on the target
model, so compression must be evaluated per target.
Weak statistics. The random baseline was run once, and the authors say the
results are indicative only.
```

```
No compression arm. It selects whole snippets and never compresses within them.
Paper 3: LLMLingua (prompt compression)
```

```
1. Core contribution. A coarse-to-fine compressor using a small LM's perplexity:
```

```
A budget controller allocates different rates to instruction, demonstrations and
question.
```

```
Whole demonstrations are dropped first.
```

```
Tokens are then pruned iteratively, conditioned on what has already been kept.
An optional distribution-alignment step tunes the small LM toward the target.
```

```
It is the closest prior work to your project.
```

# `2. Techniques and metrics` 

```
Compression rate τ = compressed/original tokens.
Exact Match for GSM8K and BBH.
```

```
BLEU/ROUGE/BERTScore against a reference answer generated by the LLM from the
original prompt (ShareGPT, Arxiv).
```

```
Latency and cost overhead, including an explicit cost model (Eq. 9, Tables 6–7).
Ablations of each component.
```

# `3. Directly usable` 

```
Per-component budgets. Instruction/question get light compression (τ_ins=0.85,
τ_que=0.9) and demonstrations take the bulk. This maps onto your information-
unit types.
```

```
Your evaluation design. Their reference-output approach is your "Answer A vs
Answer B" experiment, already used in practice.
```

```
Baselines. LLMLingua is a ready-made Strategy 3-style arm. Selective-Context,
sentence selection and LLM-generated compression give you comparison arms.
Net-cost accounting. Your dashboard lacks optimizer overhead. LLMLingua's
measured speedups only count the compressor's own time.
```

# `4. Adaptable` 

```
Importance here is perplexity, which is task-agnostic. Your "Importance(unit |
task)" is a real extension: condition on the task statement. A question-aware
variant exists in later work, but I'm recalling that from outside these three
papers, so verify it.
```

```
The budget controller's rates are hand-set and identical across tasks. Learning
or tuning them per task type is exactly your hypothesis 2, and the paper doesn't
test it.
```

```
Ablation evidence: the budget controller and iterative step mattered (EM 73.62
and 72.93 without them vs. 79.08). Per-component rates were a smaller gain
(77.26 without), and distribution alignment was small (+0.56). That suggests
building the controller and conditioning first and skipping alignment.
A GPT-2-sized small LM still reached 77.02 at 5× versus 79.08 for Alpaca-7B,
which matters for a Java service's footprint.
```

# `5. Irrelevant or low priority` 

```
Alpaca instruction tuning for alignment, GPT-4's ability to reconstruct prompts
as a product feature, and the specifics of GSM8K/BBH few-shot prompts.
```

# `6. Limitations and gaps` 

```
Compressed output is corrupted. Token pruning produces garbled text (Fig. 1) and
mangled numbers. The TOPAS case (Fig. 9) shows distorted numeric values in the
output. This directly threatens code, error messages, tables and constraints.
The benchmarks are redundancy-rich. The headline 20× comes from eight-shot
chain-of-thought demos, which are highly redundant. Your inputs (code, logs,
files) are structured differently, so the headline ratios won't transfer.
Cliffs exist. BBH fell from 70.07 to 56.85 at 7×, and all methods collapse near
25–30× on GSM8K.
```

```
Metric sensitivity is low. On ShareGPT, BERTScore differs by under 1 point
between methods (88.64–89.52). This supports your own caution about semantic
similarity as a proxy.
```

```
Text-only, no files, no multi-file code, no per-task adaptation.
Tokenizer mismatch between the small LM and the target is acknowledged.
Cross-paper comparison
```

# `Overlapping ideas` 

```
Each paper reports a fidelity-vs-compression curve with a cliff (about 10× for
optical, about 25–30× for LLMLingua on GSM8K), not a linear trade-off.
Each treats components differently (document type, prompt part, in-file vs
cross-file).
Each shows more context is not monotonically better (RepoBench/StarCoder,
DeepSeek's Gundam anomaly).
LLMLingua's Fig. 2 shows output length shrinks as compression rises. This is a
side effect your dashboard should track.
```

# `Complementary combinations (proposed)` 

```
File Analyzer: DeepSeek-style parsing for PDFs/scans → structured Markdown.
Code: RepoBench-style retrieval plus import/signature skeletons for selection.
Prose and demonstrations: LLMLingua-style budgeting and token pruning, with
protected spans (code, numbers, identifiers, hard constraints) excluded from
token dropout, since its failure mode is corrupted exact values.
```

```
Approaches worth experimental comparison at matched token budgets
```

```
Token pruning (LLMLingua).
Selection/retrieval (RepoBench-style).
Lossless re-representation (txt → Markdown, JSON → compact form). None of the
three papers test this, yet it is central to your project.
LLM-generated compression (LLMLingua showed it did poorly: GPT-4 generation
reached 27% on BBH, but that is one setting).
Optical rendering, only for multimodal targets.
Hybrids, with a rule-based baseline.
```

# `Useful metrics` 

```
Edit distance by element type for conversions.
acc@k for importance ranking against gold units.
EM/ES for code.
Reference-answer similarity (LLMLingua protocol), kept separate from
correctness.
```

```
Performance at several τ values (curves, not one point).
Net cost (optimizer time and cost plus downstream).
Output-length change.
```

# `Dataset and experiment ideas` 

```
Stratify like RepoBench: first-occurrence vs repeated critical information,
difficulty by number of distractor units, and an IF-style control where
compression shouldn't change the answer.
```

```
Create gold labels by construction, as RepoBench does with parsing. For Java
debugging, inject known bugs so the relevant lines are known. Insert canary
constraints and numbers at known positions so constraint recall can be measured
mechanically (proposed).
```

```
Bucket results by input length (RepoBench Fig. 2 shows length-dependent dips).
Use post-cutoff or private material to limit contamination.
Run multiple seeds and report variance. The papers largely don't, and several of
their gaps are under 1 point (for example 79.08 vs. 78.85).
Test per target model, since helpfulness varied by model.
```

# `Potentially novel directions (proposed)` 

```
Task-conditional importance plus task-type-specific budgets, which none of the
papers do.
```

```
Round-trip reconstruction as a retention probe. LLMLingua shows GPT-4 could
reconstruct compressed prompts but GPT-3.5-Turbo struggled. This could power
your Accept/Revise loop, but the probe model must be strong enough.
Representation selection as a first-class decision (unit type → representation),
```

```
evaluated separately from pruning.
```

```
A risk-aware compressor that spends tokens on unit types where errors are costly
(code, numbers) and compresses prose harder.
What PromptOptimizer should take from these papers
```

# `Concrete ideas to adopt` 

```
Typed budgets per information unit (LLMLingua), with importance conditioned on
the task statement.
```

```
Protected-span handling so exact values are never pruned.
Reference-answer evaluation (LLMLingua) plus gold-by-construction labels
(RepoBench).
```

```
A net-cost metric alongside token reduction.
Edit-distance-by-element for any file conversion (DeepSeek-OCR).
Compression curves per category rather than single-point results.
```

# `Experiments to run first` 

```
On a code subset, compare Jaccard, embedding and task-conditional LLM importance
using acc@k against gold units. Then measure downstream retention on RepoBench-P
and your own Java debugging set.
Compare lossless re-representation against pruning at matched budgets, since
this is the unexplored arm.
```

```
Run LLMLingua (GPT-2-sized) as a baseline on your category E prompts, and
inspect where it corrupts constraints.
```

```
Run canary-constraint tests to find where each method starts losing hard
constraints.
```

# `Research gaps to investigate` 

```
Whether the optimal compression ratio and strategy really differ by task type.
Evidence so far comes from different datasets, not a controlled test.
How exact-value fidelity degrades under each method, especially for code, logs
and tables.
```

```
Whether optical compression helps general VLM consumers at all on non-OCR tasks.
Whether importance estimated from a small model transfers across target models,
given that retrieval helpfulness varied by model in RepoBench.
```

```
I can see the three papers, but the project file's contents didn't come through
in the conversation, so I'll read it from disk first.The middle of the file was
truncated, so I'll read lines 519–690 before analyzing.I read
```

```
`prompt_optimiser_project.md` and the three papers. Two conventions run through
this analysis. **[Paper]** marks something the paper actually demonstrates, and
**[Proposal]** marks my own extension, which is untested.
```

# `Two caveats up front:` 

```
- All the empirical evidence comes from 2023-era models (GPT-3.5-Turbo,
Claude-1.3, LongChat, MPT-30B) on QA-style benchmarks. None of it covers
constraint-heavy instructions, file-format conversion, or modern long-context
models. Treat the papers as sources of hypotheses and methods, not as proof that
anything holds for your target LLM.
- I couldn't reproduce the abstract's "21.4%" boost from Table 1. The table
shows 54.1 → 71.2 (ground truth at 10th position, 4x), about +17 points. I'd
cite table values.
```

```
# Paper 1: LongLLMLingua (Jiang et al., ACL 2024)
```

```
### 1. Core contribution relevant to us
```

```
It makes compression **task-conditioned** and treats position as a variable you
```

```
can control. The closest match to your `Importance(section | task)` is this
score for each document k:
```

```
r_k = −(1/N) Σ log p(question + restrictive statement | doc_k)
```

```
This is the likelihood of the *question given the document*, scored by a small
LM. It is not the document's likelihood given the question.
```

# `### 2. Techniques and metrics` 

```
- **Reverse conditioning (Eq. 2).** [Paper] Using p(doc|question) instead gave a
large drop in the ablation (NQ at 2x: roughly 77 → 56 at the 1st position, Table
3). Plain SBERT was also worse (73.2 vs 77.2 at 1st, 66.7 vs 70.6 at 20th).
- **Restrictive statement.** [Paper] Appending "We can get the answer to this
question in the given documents" gave a small gain (~2 points on NQ, ~2 on
LongBench avg). Its main effect is reducing small-LM hallucination.
```

```
- **Contrastive perplexity (Eq. 3).** The score s_i = ppl(x_i | x_<i) − ppl(x_i
| question, x_<i) is token-level and equals conditional pointwise mutual
information.
```

```
- **Budget allocation.** A linear scheduler (Eq. 5) gives more tokens to higher-
ranked units. [Paper] Removing it cost about 2.5–3 points.
```

```
- **Reordering by importance.** [Paper] It flattens the position curve (Fig. 1b,
76.2 at every position in Table 3).
```

```
- **Subsequence recovery.** It repairs entities the LLM copies from a corrupted
prompt (Fig. 6: "Wilhelmrad" → "Wilhelm Conrad Röntgen").
```

```
- **Metrics.** Accuracy (any gold answer appears in the output), Recall@k of the
key document (Fig. 3a), compression ratio, end-to-end latency *including
compression time*, and cost per 1,000 samples (Table 9).
```

# `### 3. Directly usable` 

```
- **Reverse-likelihood importance as one scorer** in your Strategy 4. It gives
`Importance(unit | task)` a concrete, computable definition, with the task
description or user instruction playing the role of the "question".
- **Recall@k of essential units** as a cheap intermediate metric. It needs no
downstream LLM call and was used to rank scorers (BM25, Gzip, SBERT, OpenAI,
rerankers all lost to r_k).
```

```
- **Rank-based budget allocation** as a baseline against your U_i = I_i / L_i
selection.
```

```
- **Reporting latency including compressor time and cost per 1,000 samples.**
OpenAI-embedding scoring made things *slower* (0.8x speedup) and Selective-
Context 0.6x, which matters if your embedding strategy calls an API.
- **LLMLingua/LongLLMLingua as an off-the-shelf baseline.** The code is public
(aka.ms/LLMLingua).
```

# `### 4. Adaptable` 

```
- **Document-level → unit-level.** Your information units (requirements,
constraints, code blocks) are coarser than tokens and different from retrieved
passages. r_k should work there, but that is [Proposal]. It was only validated
on retrieved documents and long contexts.
```

```
- **Question → task spec.** Summarization and code tasks have weak or no
explicit "question". Notably, the paper's own gains are smallest on exactly
those tasks (Table 2, 2k budget: Summ 27.4 vs 26.5 original, Code 56.7 vs 54.2,
versus +13 on MultiDoc). Expect weaker signal on your coding and document-
transformation categories.
```

```
- **Reordering.** Use it only for independent units. Don't reorder code, ordered
steps, or CSV rows.
- **Subsequence recovery.** It matters only if you do token-level pruning. A
unit-level optimizer doesn't need it.
```

# `### 5. Irrelevant or unnecessary` 

- `Fine-tuning and distribution alignment details (LLMLingua inheritance). - The GPT2-small ablation.` 

- `The LooGLE and ZeroSCROLLS appendix details, beyond using them as sanity benchmarks.` 

```
### 6. Limitations and gaps
```

```
- **Garbled output.** Token-level compression produces unreadable text (Figs. 6–
8). In the code-completion case (Fig. 8) the compressed prompt is mangled and
the answer misses the ground truth. That is unacceptable where exact identifiers
and error messages matter.
- **Recompression per question.** It needs recompression for every question, so
the context can't be cached (the authors state this). They suggest task-level
reuse as a mitigation but don't test it.
```

```
- **Compute cost.** It needs a 7B model with token-level log-probs, which is
awkward from a Java/Spring Boot app (you would need a Python sidecar or a local
inference server).
```

```
- **Missing metrics.** No requirement- or constraint-retention metric, no multi-
constraint instruction prompts, no LLM-judge evaluation of open-ended output.
- **Possible overstatement of gains.** Much of the gain comes from baselines
degrading through position bias and distractors. A dense, all-relevant input
would show less benefit.
```

```
# Paper 2: Lost in the Middle (Liu et al., TACL 2024)
```

```
### 1. Core contribution relevant to us
```

```
It supplies the **empirical justification and a measurement protocol** for the
claim that *more context is not free*. It also gives you a way to measure
position sensitivity.
```

```
### 2. Techniques and metrics
- **Position sweep.** Hold content fixed, move the key unit across positions,
and plot accuracy.
```

```
- **Closed-book and oracle bounds.** GPT-3.5-Turbo: 56.1% closed-book, 88.3%
oracle. [Paper] Mid-context accuracy fell *below closed-book* in the 20- and 30-
document settings.
```

```
- **Robustness criterion.** A model robustly uses context only if the gap
between best-case and worst-case position is small.
```

```
- **Synthetic key-value retrieval.** UUID pairs in JSON; a controlled testbed
for retrieval without semantics.
```

```
- **Query-aware contextualization.** Placing the query before *and* after the
data gave near-perfect key-value retrieval (worst case otherwise 45.6%), but
**minimal change on multi-document QA**.
```

```
- **Retriever–reader saturation.** Going from 20 to 50 documents added only ~1.5
points (GPT-3.5) and ~1 point (Claude-1.3), while cost and latency rose.
```

```
### 3. Directly usable
```

```
- **Oracle and closed-book baselines per dataset instance.** The oracle prompt
contains only the annotated essential units, which gives a ceiling for any
compression strategy. Closed-book gives the floor. Report your optimizer on that
interval.
- **Position-robustness gap** (max − min over positions) as a metric. Your
Category E prompts are the natural place for it.
- **Both distractor types.** Retrieved hard-negative distractors and random
distractors both showed the effect (Appendix B), so include both.
- **Ranked-list truncation and reranking** as named baselines: sort units by
importance and cut at a budget.
```

```
- **Duplicating the task statement at the start and end** as a cheap lossless
transformation. [Paper] It is free on key-value tasks and neutral-to-slightly-
negative on multi-doc QA, so test it per task category rather than assuming it
helps.
```

```
### 4. Adaptable
```

```
- **Key-value task as a micro-benchmark for your format work.** [Proposal]
Serialize the same facts as verbose JSON, compact JSON, YAML, or CSV, and
compare accuracy and token count. This isolates "does the representation affect
retrieval" from task semantics.
```

```
- **Positional sweeps on code and constraints.** [Proposal] Move a hard
```

```
constraint to the start, middle, or end of a long prompt and measure violation
rate. The paper only tested factual QA, so whether instruction-following
constraints behave the same is unknown.
```

# `### 5. Irrelevant or unnecessary` 

```
- Encoder-decoder vs decoder-only analysis (you call a black-box external LLM).
```

```
- Llama-2 size and fine-tuning ablations.
```

```
- The serial-position psychology and the instruction-tuning comparison. They
explain *why*, which you don't need to ship a system.
```

# `### 6. Limitations and gaps` 

```
- **Outdated models.** The effect size is measured on 2023 models. Newer long-
context models may show a smaller or different curve. **You must re-measure on
your target LLM before assuming position bias applies.**
```

```
- **Narrow tasks.** Single-answer retrieval only, scored by substring match. No
code, no multi-requirement tasks, no open-ended generation.
- **No method.** It diagnoses but offers no optimizer.
```

```
# Paper 3: Prompt Compression Survey (Li et al., NAACL 2025)
```

```
### 1. Core contribution relevant to us
```

```
It provides **taxonomy and vocabulary**: hard prompt methods (filtering,
paraphrasing) versus soft prompt methods (learned embeddings or KV tokens). It
reports **no experiments**, so it cannot be used as evidence of effectiveness.
```

# `### 2. Techniques and metrics` 

```
- **Filtering.** SelectiveContext (self-information over lexical units merged
into noun phrases) and LLMLingua (small-LM perplexity, separate budgets for
instruction, demonstrations, and question, with numbers and units preserved).
Both are described briefly. Variants include LLMLingua-2 (distilled token
classifier), TACO-RL and PCRL (RL token selection), and CPC and TCRA-LLM
(embedding-based).
```

```
- **Paraphrasing.** Nano-Capsulator rewrites the prompt into fluent natural
language, trained with a semantic-preservation loss and a downstream-utility
reward. CompAct and FAVICOMP are the other entries.
```

```
- **AdaComp.** It selects documents adaptively based on query difficulty and
retrieval quality.
```

```
- **Evaluation convention.** Compression ratio = original tokens / compressed
tokens. Compare methods at equal ratio, or find the maximum ratio at comparable
performance.
```

# `### 3. Directly usable` 

```
- **The taxonomy for positioning your work.** Rule-based and embedding-based
unit dropping = filtering. LLM rewriting = paraphrasing. Your project is mostly
hard-prompt methods, because you target an external API LLM.
```

```
- **Both evaluation conventions** (fixed ratio and max ratio at matched
performance). They fit your central research question directly.
```

```
- **Prompt component structure.** Instruction/context/question, with
demonstrations added for few-shot. Budgets can be assigned differently per
component, as LLMLingua's budget controller does. This matches your segmentation
step.
- **Candidate baselines to cite and compare:** SelectiveContext,
LLMLingua/LongLLMLingua, and a paraphrase-style rewrite for your "LLM-based"
strategy.
```

# `### 4. Adaptable` 

```
- **Paraphrase category as the nearest relative to representation
transformation.** All the paraphrasing methods are lossy and tuned for natural
text. Your lossless/lossy distinction and structured-file handling go beyond
them.
```

```
- **The survey's noted failure mode** (filtered prompts lose grammaticality and
give the LLM an unfamiliar input distribution). [Proposal] Turn this into a
```

```
measurable check: compare unit-level pruning to token-level pruning at equal
ratio.
```

# `### 5. Irrelevant or unnecessary` 

```
Nearly all of Section 4 (soft prompts, GIST, ICAE, 500xCompressor, xRAG,
UniICL), and the discussions of attention optimization, KV compression,
hard+soft combination, and multimodality. They require white-box access or
training a compressor. Your architecture calls an external LLM.
```

# `### 6. Limitations and gaps` 

```
- No quantitative comparison and no benchmark guidance beyond "same ratio or max
ratio".
```

```
- No treatment of code, CSV/JSON, PDFs, or file-format conversion.
```

- `No notion of requirement or constraint preservation. "Information loss" is named as a challenge but not measured.` 

```
- Treats all compression as lossy.
```

# `# Cross-paper comparison` 

# `### Overlapping ideas` 

```
- **Task or question conditioning** is the shared principle (LongLLMLingua's
r_k, SelectiveContext/LLMLingua as the task-agnostic contrast). Both Papers 1
and 2 show that less-but-relevant context can match or beat the full context.
```

```
- **Position matters.** Paper 2 shows it, and Paper 1 exploits it.
- **Coarse-to-fine.** Select units first, then optionally prune within them.
Your design is the coarse stage; token-level is optional.
```

```
- **Cost accounting.** Both Paper 1 and the survey stress that the compressor's
own cost can erase the gain.
```

# `### Complementary combinations` 

```
- Paper 2's position sweep as the **test harness** for Paper 1's reordering and
importance scoring. Together they let you ask whether the optimizer's output is
also position-robust.
```

```
- Paper 1's scorers feeding Paper 3's taxonomy as **named strategy families** in
your experiments.
- Paper 2's oracle and closed-book bounds to **normalize** Paper 1-style gains.
Example: report where a strategy lands between floor and ceiling rather than
only vs original.
```

# `### Approaches worth comparing experimentally` 

```
| Compare | Why |
```

```
|---|---|
```

```
| r_k (reverse likelihood) vs LLM-judge importance vs embedding relevance vs
rules | Core of your Strategy 2/3/4 |
```

```
| Unit-level drop vs token-level pruning (LLMLingua) at equal ratio | Tests
whether coarse-only is safer for code and constraints |
```

```
| Importance-ordered vs original-order output | Tests Paper 1 and 2's position
claim on your tasks |
```

```
| Task statement at start only vs start+end | From Paper 2; expect task-
dependent results |
```

```
| LLM paraphrase/summary vs unit selection | Your "generic prompt rewriting"
baseline |
```

```
| Random unit drop vs importance-guided drop at same ratio | Sanity control that
none of the papers reports |
```

# `### Useful metrics and evaluation methods` 

```
- **Retention curve**: performance versus compression ratio (e.g. 2x, 4x, 6x),
not a single point.
- **Oracle-normalized score**: (optimized − closed-book) / (oracle − closed-
book).
```

```
- **Unit recall@budget**: fraction of annotated essential units kept. It is
cheap and can be validated against downstream performance.
```

```
- **Position-robustness gap** over key-unit placements.
```

```
- **Cost per 1,000 samples** and latency including optimizer time.
```

```
- **Noise floor**: rerun the *original* prompt several times so you know how
much "retention" differences are just variance. The papers use temperature 0 and
greedy decoding; adopt that, but also check judge variance. [Proposal]
- **Allow retention above 100%.** Paper 1 shows compressed prompts can beat the
original on noisy inputs, so don't cap the metric.
```

# `### Ideas for your dataset and experiments` 

```
- **Add essential-unit annotations** (spans) to your schema, alongside
requirements and constraints, so unit recall is computable. [Proposal]
- **Add position metadata and sweeps** for Category E, so each hard constraint
can be moved among start, middle, and end.
```

```
- **Include both noisy and dense inputs.** Paper 1's gains are concentrated
where relevant information is sparse. Dense code may show the opposite.
- **Multiple tasks per shared context**, to test the caching limitation Paper 1
admits.
```

```
- **Regression-test against public benchmarks** (NQ-20 docs, LongBench, MuSiQue)
to confirm your pipeline can reproduce the published LongLLMLingua trends before
trusting results on your own data.
```

# `### Potentially novel directions` 

```
All of these are **[Proposal]**. None is demonstrated in the three papers, and I
haven't checked the wider literature for prior work.
```

```
1. **Constraint-protected compression.** Units above an importance threshold, or
matching hard-constraint, exact-error-message, and numeric patterns, are never
altered. Only unit-level dropping is allowed elsewhere. This directly addresses
the failure the papers show for token-level pruning.
```

```
2. **Counterfactual importance labels.** Remove one unit at a time, measure the
downstream drop, and use that as ground truth to calibrate or learn the α…ε
weights in your utility formula. This replaces guessing importance with
measuring it, at a cost of N calls per instance (use a subset).
```

```
3. **Lossless transformations as their own arm.** Format conversion and layout
(txt→Markdown, verbose→compact JSON, reordering, task duplication) evaluated
separately from lossy dropping. No paper separates the two.
```

```
4. **Retention-guarded revise loop.** Your architecture has Accept/Revise.
Concretely: deterministically check protected spans, then use an LLM to check
requirement recall, and add back the highest-importance dropped unit if
retention falls below the threshold. No paper has a verifier.
```

```
5. **Adaptive budget per task type.** AdaComp (survey) and ranked-list
truncation (Paper 2) suggest choosing how much to keep dynamically rather than
fixing a ratio.
```

# `# What PromptOptimizer should take from these papers` 

```
**Concrete ideas to adopt**
```

```
- Define task-conditional importance as reverse likelihood: score unit u by −log
p(task description | u) with a small LM, and test it against an LLM-judge scorer
and embeddings.
```

```
- Allocate budget by importance rank (Eq. 5-style) and compare to your U_i = I_i
/ L_i selection.
- Use oracle and closed-book prompts as the ceiling and floor for every dataset
instance.
- Report retention curves at several ratios, plus unit recall, position-
robustness gap, cost per 1,000, and latency including optimizer time.
```

```
- Order and duplicate the task statement as a lossless option, but verify it per
task category.
```

# `**Experiments to run next**` 

`1. Reproduce a LongLLMLingua-vs-baselines trend on NQ-20 and one LongBench subset to validate your harness.` 

```
2. Run position sweeps of a hard constraint on your own Category E prompts with
```

```
your target LLM, to check whether the U-shape exists at all.
```

```
3. Compare unit-level vs token-level pruning on code tasks at equal ratio.
```

```
4. Run the format micro-benchmark (JSON vs compact vs CSV/YAML) using Paper 2's
key-value setup.
```

```
5. Compare importance-guided vs random unit dropping at equal ratio.
```

```
**Research gaps to investigate**
```

- `Whether position bias and distractor sensitivity persist in current models.` 

- `How to score open-ended outputs reliably (the papers use only automatic metrics; your LLM-judge needs its own validation).` 

```
- Whether embeddings detect *redundancy* between units. The papers use
embeddings for *relevance to a query*, which is a different job.
```

- `Whether task-level importance can be cached and reused across tasks sharing a context.` 

```
- Whether format and layout transformations measurably change both token cost
and accuracy.
```

```
**Papers or components to deprioritize:** soft-prompt methods, encoder-decoder
analysis, and Llama-2 scaling results.
```

```
The Self-RAG and Sentence-BERT papers are already in context, but the SWE-bench
PDF isn't, so I'll read the file-reading skill first and then load it from
disk.The project file looks unchanged (same sections, 1,207 lines). I analyzed
all three papers against it. **[Paper]** marks what a paper demonstrates, and
**[Proposal]** marks my own extension, which is untested. Two caveats apply
throughout. All three papers use 2019–2023 models and benchmarks, so none of
their numbers is evidence about your target LLM. None of them studies
compressing user-supplied multi-constraint inputs.
```

```
# Paper A: Self-RAG (Asai et al., 2023)
```

# `### 1. Core contribution for us` 

```
The training method is not what matters to you. The useful part is the
**decomposition of "is this good?" into separate judgments**: is outside
information needed (Retrieve), is a piece relevant to the task (IsRel), is the
output supported by the evidence (IsSup), and is the output useful overall
(IsUse). The paper also shows these judgments can be combined with tunable
weights at inference time.
```

# `### 2. Techniques and metrics` 

```
- **Probability-based scores.** [Paper] IsRel = p(Relevant) / (p(Relevant) +
p(Irrelevant)). IsSup = p(Fully) + 0.5·p(Partially), normalized. IsUse is a
weighted sum over its 1–5 labels.
```

```
- **Weighted combination.** Segment score = generation probability + Σ weight ×
critique score. Default weights are 1.0, 1.0 and 0.5. Hard filtering is the
alternative (drop a segment if IsSup = "no support").
```

```
- **Critic validation.** GPT-4 labels were spot-checked by the authors on only
20 instances per dimension (relevance 95%, retrieval need 95%, support 90%,
usefulness 80%). The distilled Llama-2-7B critic's agreement with GPT-4 was 93.8
(Retrieve), 93.5 (IsSup), 80.2 (IsRel) and 73.5 (IsUse).
- **Human evaluation** of 50 outputs.
```

```
- **Ablations** at 50k training instances.
```

# `### 3. Directly usable` 

```
- **The IsRel and IsSup prompts** (Tables 10–11) as starting templates for your
LLM-importance scorer (Strategy 3) and your retention checker.
```

```
- **Probability-normalized scores instead of asking an LLM for "0–1".** This
needs token log-probabilities from your API. Check whether it exposes them,
since many don't. If not, a local small model is the fallback.
```

```
- **Judge validation protocol.** Report agreement with human labels per judging
dimension. The paper's own numbers show the holistic dimension (IsUse, 73.5–80%)
```

```
is the least reliable. That argues against collapsing your evaluation into one
"answer quality = 92" score.
- **Support check as a loss detector.** [Proposal] For each claim in answer B,
test whether the optimized input supports it. Claims supported only by the
original input point to dropped information. Claims supported by neither point
to hallucination risk.
```

# `### 4. Adaptable` 

```
- **"Retrieve on demand" → "needs-context?" per task.** Creative writing needs
little source material. [Paper] The value of retrieval varies by task (Fig. 3c:
PubHealth accuracy barely moves as retrieval frequency falls, PopQA accuracy
drops more). That is directionally consistent with your hypothesis 2, but it
concerns retrieval, not compression.
```

```
- **Weighted selection.** It maps onto your α…ε utility formula, giving a user-
facing knob. [Paper] Fig. 3b shows the weight trades citation precision against
fluency, but only on one task (ASQA).
```

```
- **Per-unit "continue to use evidence" decisions → marginal-value selection.**
[Proposal] Keep or drop a unit given the units already kept.
```

```
- **Hard constraints as filters** → your protected-unit rule.
```

# `### 5. Irrelevant` 

```
Generator training on reflection tokens, Llama-2 fine-tuning, the
Wikipedia/Contriever setup, the ALCE, FactScore and MAUVE metrics, and the QA
results themselves. They all require a trainable model, and yours is external.
```

# `### 6. Limitations and gaps` 

```
- **Wrong operation.** It selects retrieved passages for generation. It does not
compress user-supplied input, and it reports no token or cost reduction.
- **Small samples.** The ablations use 150 ASQA samples, and the human study
uses 50.
```

```
- **Narrow task set.** Factual QA only, with no code and no multi-constraint
instruction tasks.
```

```
- **Weak critic for relevance.** The distilled critic is only 80.2% on IsRel,
which is your most important dimension.
```

# `# Paper B: Sentence-BERT (Reimers & Gurevych, 2019)` 

# `### 1. Core contribution for us` 

```
It is the efficiency argument for Strategy 2. Embed each unit once and compare
with cosine, instead of running a pairwise cross-encoder (for 10,000 sentences,
about 65 hours versus about 5 seconds). It also shows that raw BERT embeddings
are poor for cosine similarity (STS average 54.81, and 29.19 for the CLS vector,
both below averaged GloVe at 61.32).
```

# `### 2. Techniques and metrics` 

```
- **Training objectives:** siamese network with mean pooling (default), and
classification, regression and triplet objectives.
```

```
- **Evaluation:** Spearman rank correlation of cosine similarity against human
labels. The authors argue explicitly against Pearson.
```

```
- **Cross-topic evaluation** on argument similarity.
```

```
- **Smart batching** (group sentences of similar length). Speed rose from 44 to
83 sentences/s on CPU and from 1,378 to 2,042 on GPU, on 2019 hardware.
```

# `### 3. Directly usable` 

- `**Bi-encoder + cosine as the candidate generator** for redundancy detection. The cost scales well with file size.` 

- `**Spearman correlation** to validate your redundancy score against human duplicate labels. It is threshold-free.` 

- `**Hold-out-by-category evaluation.** The cross-topic setup (train on two topics, test on the third) is the pattern for your categories A–E. [Paper] Performance dropped about 7 points Spearman in the cross-topic setting.` 

- `**Length-sorted batching.** It is trivial and effective.` 

```
- **Two-stage verification.** [Paper] The cross-encoder BERT beats SBERT in the
cross-topic argument setting (57.23 vs 50.65 ρ for base, 60.34 vs 53.10 for
large). With NLI+STSb training, BERT scores 88.33 versus 85.35 for SBERT-base.
So SBERT is the cheap filter and a more accurate pairwise model should confirm.
The two-stage pipeline itself is [Proposal].
```

# `### 4. Adaptable` 

```
- **From similarity to redundancy.** The paper validates "how similar", not "can
one unit be dropped without information loss". You need your own labeled pairs:
equivalent, one subsumes the other, related-but-distinct, and contradictory.
[Proposal]
```

```
- **Document-level similarity metric** (your metric #4). [Proposal] Use
coverage: for each original unit, take its best cosine match among optimized
units. This is a BERTScore-style idea from background knowledge, not from these
papers.
```

```
- **Model choice.** Use a current embedding model, not the 2019 NLI weights. The
paper cannot tell you which. (LongLLMLingua's SBERT baseline used all-mpnet-
base-v2.)
```

```
- **Weak-label construction.** The Wikipedia-section triplet idea (same section
= positive, different section = negative) can bootstrap topical-segmentation
labels from your own documents. [Proposal]
```

# `### 5. Irrelevant` 

```
NLI training, SentEval transfer tasks, the pooling and concatenation ablations,
and the 2019 speed numbers except as a reminder to benchmark your own
deployment.
```

# `### 6. Limitations and gaps` 

```
- **Natural-language sentences only.** There is no code, logs, JSON, CSV or
identifiers, and no paragraph-level units.
```

```
- **Constraint-critical distinctions are untested.** The paper has no tests of
negation, quantifiers, numbers, or "do / do not" polarity, which is what
constraints depend on. I am not claiming SBERT fails on them, only that this
paper gives you no evidence either way.
```

```
- **Topical closeness is not equivalence.** The authors note that similar
arguments must share claims *and* reasoning. That is the same gap as "same topic
≠ redundant".
- **Domain shift.** The shortfall on SICK-R is attributed to training-data
differences, and the cross-topic drop shows limited generalization.
- **No threshold guidance** for declaring duplicates.
```

```
- **Java integration.** A Java app needs an ONNX, DJL or sidecar runtime. That
is an implementation note, not from the paper.
```

```
# Paper C: SWE-bench (Jimenez et al., ICLR 2024)
```

# `### 1. Core contribution for us` 

```
Two things. It is a model for **execution-verified evaluation** of coding tasks.
It also gives direct evidence about **context selection for code**, including an
oracle that shows how much perfect localization would buy.
```

```
### 2. Techniques and metrics
```

```
- **Metrics.** % Resolved (fail-to-pass tests now pass, and about 51 median
existing tests still pass) and % Applied (the patch applies cleanly).
- **Three-stage instance construction:** scrape pull requests, filter by
attributes (resolves an issue, contributes tests), and filter by execution (at
least one fail-to-pass test, installs and runs).
```

```
- **Context settings:** BM25 at 13k, 27k and 50k tokens, "oracle" files (those
edited by the gold patch), and "oracle-collapsed" (oracle files with everything
outside ±15 lines of the edited lines collapsed).
```

```
- **Input template** with `<issue>` and `<code>` blocks and per-file start and
end markers.
```

```
- **Temporal split** (before vs after 2023) to check contamination.
```

# `### 3. Directly usable` 

```
- **Fail-to-pass and pass-to-pass tests** for your coding category. [Paper]
defines them. The mapping is mine: pass-to-pass tests operationalize constraints
like "do not rewrite working modules", and fail-to-pass tests operationalize
requirements. Report them separately.
```

```
- **% Applied as a cheap validity gate**, analogous to compile or schema
validity.
```

```
- **The context ladder as a baseline set:** your selector versus file-level
oracle versus line-level oracle. The gaps bound what any selector or intra-file
compressor can gain.
```

```
- **[Paper] More recall did not help.** BM25 recall of oracle files rose from
29.6 to 44.4 to 51.1 as context grew (Table 3), yet Claude 2's resolve rate fell
from 1.96 to 1.87 to 1.22 (Table 2). This corroborates the "more context isn't
better" finding from the earlier papers, now in code.
```

```
- **[Paper] Collapsing to the relevant lines helped every model.** Claude 2 went
from 4.80 to 5.93, GPT-4 from 1.74 to 3.40 and ChatGPT-3.5 from 0.52 to 1.09.
But the paper's text compares GPT-4's BM25 result (1.3%) with the collapsed
result (3.4%), mixing settings. Cite the tables.
```

```
- **Output-scope tracking.** [Paper] Model patches were much shorter and touched
fewer files than gold patches (Table 8). Tracking answer scope between original-
input and optimized-input runs is a cheap retention signal. Applying it that way
is [Proposal].
```

# `### 4. Adaptable` 

```
- **Oracle-collapsed → structural Java collapsing.** [Proposal] Keep class and
method signatures and imports, and collapse bodies of units judged irrelevant.
Compare it with a ±15-line window. The oracle uses the gold patch, so it is an
upper bound, not an achievable compressor. The real question is how close your
importance estimator gets.
```

```
- **Contamination and closed-book arm.** If a model answers from memory,
compression will look free. Include a closed-book run so retention is read
against the floor.
```

```
- **Use SWE-bench as a stress test, not the primary benchmark.** The paper's
best BM25 rate was 1.96% (Claude 2) and 3.79% (Claude 3 Opus). That is roughly
45, 43 and 28 solved instances across the three context limits, so retention
ratios will be noisy. Current systems likely score far higher, but I haven't
checked the leaderboard.
```

```
- **It is Python-only.** Your project needs Java tasks, and I haven't verified
what Java equivalents exist.
```

# `### 5. Irrelevant` 

```
SWE-Llama fine-tuning and LoRA details, per-repository breakdowns, conda
environment engineering, issue-tag analysis, the Sphinx case walkthrough, and
the agent/tool-use future work (unless you go agentic).
```

# `### 6. Limitations and gaps` 

```
- **Floor effects and no variance.** Very low baseline accuracy, one greedy
sample per instance, and no confidence intervals.
- **No realistic compressor.** It evaluates only retrieval and an oracle, and it
does not compress the issue text or study token reduction.
```

```
- **Untested assertion.** The claim that dense retrieval is "ill-suited" to this
setting is stated, not tested. BM25 itself found any oracle file only about 51%
of the time at 27k, and none in almost half of cases.
```

```
- **Confounded fine-tune result.** SWE-Llama's poor BM25 results are attributed
to a context distribution shift, which suggests (but doesn't prove) that models
can be sensitive to how input is formed.
```

```
- **Tests miss quality.** The authors acknowledge that passing tests does not
capture readability or comprehensiveness.
```

```
# Cross-paper comparison
```

```
### Overlapping ideas
```

```
- **Three importance estimators at three price points**, mapping to your
Strategies 1–3: lexical (BM25, SWE-bench), embedding (SBERT), and LLM critic
(Self-RAG).
- **Less but relevant beats more.** Self-RAG (irrelevant passages hurt
generation) and SWE-bench (recall up, success down) agree with the earlier
papers.
```

```
- **Oracle bounds** (SWE-bench's oracle files, the earlier Lost in the Middle
oracle).
- **Cheap first, accurate second:** SBERT versus a cross-encoder, and a
distilled critic versus GPT-4.
```

```
### Complementary combinations
```

```
- **Cascade** [Proposal]: rules and BM25, then SBERT dedup and relevance, then
the LLM critic only on borderline units, then a support check on the final
answer. Escalate by score margin to control cost.
```

```
- **Directional-support redundancy** [Proposal]: instead of symmetric cosine,
ask an IsSup-style question: "is unit A fully supported by unit B?" This handles
subsumption (A inside B), which cosine cannot distinguish from mutual
paraphrase.
```

```
- **Self-RAG weights on top of the SWE-bench ladder:** a user-tunable knob
trading compression against retention, tuned per task type.
```

```
### Approaches worth comparing experimentally
```

```
| Question | Candidates |
```

```
|---|---|
| Redundancy detection | cosine threshold vs cross-encoder vs LLM support check
vs rules |
```

```
| Unit importance | BM25 vs embedding similarity vs probability-normalized LLM
score vs LongLLMLingua's r_k |
```

- `| Code context | file-level BM25 vs embedding vs LLM localization vs structural collapsing |` 

```
| Judging | single holistic score vs decomposed
relevance/support/requirement/constraint scores |
```

```
| Pipeline | single-stage vs cascade |
```

```
### Useful metrics and evaluation methods
```

- `**Success vs regression, separately:** fail-to-pass rate and pass-to-pass rate, plus % Applied as a validity gate.` 

```
- **Per-dimension judge agreement** with a small human-labeled set.
```

```
- **Claim-loss rate** from the support check.
- **False-merge rate** for deduplication. Merging distinct units loses
information, so weight it above recall. [Proposal]
```

```
- **Spearman for score validity**, with held-out-category evaluation.
```

```
- **Output-scope drift** (answer length, number of files edited).
- **Instance counts and paired comparisons.** SWE-bench's tiny counts are the
warning. Add a rerun noise floor.
```

```
### Ideas for the dataset and experiments
```

```
- **Seeded-bug Java tasks with JUnit fail-to-pass and pass-to-pass tests.** A
passing build is the "applied" check.
- **Adversarial minimal pairs** for the redundancy detector: "do" vs "do not",
changed numbers, one changed identifier.
```

```
- **Automatic essential-unit labels for code.** Units touched by the reference
fix are essential (the oracle idea). Other categories need manual labels.
- **Closed-book and oracle arms** per instance.
```

```
- **Human-labeled judge-calibration set** (about 50–100 items per dimension).
- **Tune on categories A–D, test on E.**
```

# `### Potentially novel directions (all [Proposal])` 

```
1. Redundancy as directional support rather than symmetric similarity.
2. Cascaded, calibrated, probability-based importance scores with a per-task
weight vector.
```

`3. **Test-derived importance for code.** Use execution coverage of the fail-to-` 

```
pass and pass-to-pass tests (for example JaCoCo in Java) as automatic importance
labels and as a protection rule. SWE-bench does not do this.
```

```
4. **Validate your own proxy metrics.** Test whether unit recall and
requirement/constraint recall actually predict test-based success. That would
tell you whether cheap metrics can stand in for downstream LLM runs.
```

```
# What PromptOptimizer should take from these papers
```

# `**Concrete ideas to adopt**` 

```
- Use decomposed judge dimensions (relevance, support, requirement recall,
constraint recall) with per-dimension human-agreement checks, not one holistic
quality score.
```

```
- Score unit importance with probability-normalized judgments if your API
exposes log-probs. Otherwise use a local small model.
```

```
- Build redundancy detection as bi-encoder candidates followed by a verifier,
and gate merges on directional support.
```

```
- Evaluate code tasks with fail-to-pass and pass-to-pass tests, and report the
two separately.
```

```
- Use a baseline ladder of closed-book, your selector, file-level oracle and
line-level oracle.
```

```
**Experiments to run next**
```

```
1. Adversarial minimal-pair test of your embedding model for polarity, number
and identifier changes.
```

`2. Cosine threshold vs cross-encoder vs LLM support check on a labeled duplicate set, measured by false-merge rate.` 

```
3. Structural Java collapsing vs a ±15-line window vs whole files, on seeded-bug
tasks.
```

```
4. Cascade vs single-stage cost-versus-retention curves.
```

```
5. Correlation between unit recall and test-based success.
```

- `**Research gaps to investigate**` 

```
- Whether embedding similarity tracks "safe to drop" for constraints, code and
structured data. None of these papers tests it.
```

- `Whether coverage-derived labels beat LLM-judged importance for code. - How reliable your LLM judge is on your own categories. The paper's numbers suggest holistic judgments are the weakest.` 

```
- Whether any of these effects hold for current models. All three papers measure
2019–2023 systems.
- Which Java task benchmarks exist, so you don't have to build everything
yourself.
```

```
**Deprioritize:** Self-RAG's training machinery, SBERT's training and transfer
evaluation, and SWE-Llama and repository-engineering details.
```

```
I've read your project spec and extracted the book's text (599 pages, an Aug
2024 draft). Next I'm mapping its chapter structure so I can search for the
relevant sections.I've read the parts of the book that bear on your project.
About 50-60 of its 599 pages matter. Page numbers below are the book's printed
numbers (add 7 for the PDF page). This is an August 2024 draft: §12.7 (RLHF) is
literally marked "TBD", and it has no coverage of long-context models, LLM-as-
judge or prompt caching.
```

# `# Concepts that matter` 

# `## Essential` 

- `**1. Automatic prompt optimization as search**` 

- `**Where:** §12.5, pp. 255-257 (DSPy mentioned p. 247) - **Book says:** Every such system has a start prompt, a scoring metric and an` 

```
expansion method, usually run as beam search. Candidates are scored by
"execution accuracy": run the prompt on a small labeled sample and grade it with
a task metric. Scoring is the inner loop, so its cost is the bottleneck.
Expansion can be blind paraphrase, or failure-driven: collect failures, have an
LLM critique the prompt, then rewrite it.
```

```
- **For PromptOptimizer:** Your "Quality/Retention Check → Accept/Revise" loop
is this search with a different objective. The book optimizes accuracy only, so
you must add token cost to the score. Failure-driven expansion maps to "this
deletion broke the answer, so restore it."
```

```
- **Evaluation idea:** Plot accuracy against iterations. Compare blind with
failure-driven expansion. Count the optimizer's own LLM calls as cost.
```

```
**2. Few-shot demonstrations**
- **Where:** §12.1.1, pp. 246-247
- **Book says:** Most of the gain comes from the first example, with diminishing
returns after. Examples mainly teach the task and output format, not facts, and
even wrong-answer demos can help. Too many make the model overfit to their
details. Demos similar to the current input help, and the best set is chosen by
measured performance.
```

```
- **For PromptOptimizer:** Your "Example → 0.60" weight is a guess. The book
suggests most examples compress to one or two, but examples that carry format or
style (your creative and structured tasks) are the point of the prompt. That
fits your task-dependence hypothesis.
```

```
- **Evaluation idea:** Run a retained-examples curve (0, 1, 2, all) per
category. The book's evidence comes from classification and QA, so don't assume
it transfers.
```

```
**3. Task metrics for answer equivalence**
- **Where:** §12.6 p. 258; §14.4 pp. 304-306
- **Book says:** Exact match for single gold answers, token-level F1 for free
text, mean reciprocal rank (MRR) for ranked answers.
- **For PromptOptimizer:** These cover your extraction, structured and QA
categories.
```

```
- **Evaluation idea:** Score answers against gold and also against the original-
input answer, and report the gap. The book offers nothing for code or report-
style outputs.
```

```
**4. Precision, recall and F-measure, with macro vs micro averaging**
- **Where:** §4.7, pp. 66-69; §14.1.4, pp. 295-298 (ranked precision/recall,
MAP)
```

```
- **Book says:** Precision and recall are computed against gold labels. Macro-
averaging treats each class equally, while micro-averaging pools all items.
- **For PromptOptimizer:** Your Requirement Recall and Constraint Recall have no
precision partner. Add precision to catch optimized text that distorts or
invents requirements. Macro-average across task categories so large categories
don't dominate.
```

```
- **Evaluation idea:** Use F-β with β>1 for constraints. chrF sets precedent by
weighting recall twice as much (p. 282).
```

```
**5. Significance testing and dev/test discipline**
- **Where:** §4.8-4.9, pp. 69-72; §13.6.2 p. 283 (bootstrap confidence
intervals); §3.2 p. 38
- **Book says:** Tune on a dev set and keep the test set untouched; run cross-
validation inside the training portion. Compare two systems with a paired
bootstrap on per-example differences, reporting the effect size and p-value.
- **For PromptOptimizer:** Every "Hybrid > LLM > Rule" claim in your §11 is a
paired comparison on the same items. Tune α…ε on dev only.
```

```
- **Evaluation idea:** Run paired bootstrap per category per strategy pair, with
confidence intervals on retention. The book assumes deterministic systems, so
repeated runs for LLM variance (temperature, pp. 209-210) are your problem.
```

```
**6. Evaluation dataset design**
- **Where:** §2.3 p. 16 (datasheets); §4.10 p. 74 (model cards); §14.3.2 pp.
303-304 (QA dataset dimensions); §12.3 pp. 253-254 (leave-one-cluster-out)
```

```
- **Book says:** Document a corpus by motivation, situation and collection
process. QA datasets vary along natural vs probing questions, open- vs closed-
book, multiple-choice vs free-form, and zero- vs few-shot. Instruction-tuning
evaluation holds out a whole task cluster.
```

```
- **For PromptOptimizer:** Your tasks are open-book (the answer depends on the
supplied context), so label them that way and write a datasheet. Leave-one-
category-out directly tests your second hypothesis, that the best strategy
depends on task type.
- **Evaluation idea:** Tune on four categories, test on the fifth, and compare
against in-category performance.
```

# `**7. Tokenization (BPE)**` 

```
- **Where:** §2.5, pp. 18-23; §2.6 p. 23
- **Book says:** LLMs use learned subword tokens. Frequent strings become single
tokens, and rare or unseen strings split into pieces. Tokenization varies by
language, and perplexity and BLEU are tokenizer-sensitive (pp. 215, 283).
- **For PromptOptimizer:** Token Reduction % must be computed with the target
model's tokenizer. Format changes (Markdown, compact JSON, comment stripping)
shift counts unpredictably, and identifiers, numbers and non-English text likely
fragment (my inference from BPE).
- **Evaluation idea:** Report character and token reduction side by side, per
target model and per file type. The book has no data on the actual token cost of
formats.
```

```
**8. TF-IDF, BM25 and cosine**
- **Where:** §6.4-6.5, pp. 110-113; §14.1, pp. 291-295
- **Book says:** TF-IDF weights log term frequency by rarity across documents,
so ubiquitous words score about zero. Cosine normalizes for length. BM25 adds
term-frequency saturation and length normalization (typical k = 1.2-2, b =
0.75). Weakness: vocabulary mismatch.
```

```
- **For PromptOptimizer:** This is the cheapest working version of
Importance(section | task): treat the task description as the query and score
chunks against it. Chunk-to-chunk cosine gives a redundancy score. It costs no
LLM calls and is interpretable. Exact-match on identifiers and error strings
should make it strong for code and logs (my inference).
- **Evaluation idea:** Measure recall@k of the chunks annotated as needed, and
calibrate the redundancy threshold on labeled duplicate pairs.
```

```
**9. Retrieve-and-read (RAG), dense retrieval, chunking**
- **Where:** §14.2-14.3, pp. 298-303
- **Book says:** Dense embeddings fix vocabulary mismatch. A cross-encoder
(joint query+document) is most accurate but too costly except for reranking. A
bi-encoder precomputes document vectors, which is cheaper but less accurate. The
common compromise is a BM25 first pass, then reranking the top N. ColBERT and
approximate nearest-neighbor search (Faiss) are the in-between options.
Documents are split into fixed passages of about 100 tokens (p. 299). Prompt
details such as how to delimit passages are left open, and it flags privacy
concerns when private data goes to an externally hosted LLM.
```

```
- **For PromptOptimizer:** You are effectively a retriever whose corpus is the
user's attachments and whose query is the task. The cheap-filter-then-costly-
rerank pattern is a ready-made cost structure for your Hybrid strategy. The
privacy point applies to your attachments too.
```

```
- **Evaluation idea:** Compute MRR, MAP and recall@k of selected chunks against
annotated needed chunks, plus latency per stage. Bracket end-to-end results with
three baselines: no attachments (closed-book), full context, and optimized.
```

- `**10. Anisotropy (cosine caveat)** - **Where:** §11.3.2, pp. 233-234` 

```
- **Book says:** Raw cosine on contextual embeddings is inflated, because random
word pairs score near 1 when vectors share a dominant direction. Z-scoring
helps. Cosine still underestimates similarity for very frequent words.
```

```
- **For PromptOptimizer:** Fixed redundancy thresholds in Strategy 2 and your
"semantic similarity" metric can mislead. The book discusses word-in-context
vectors, not sentence-embedding models, so treat this as a warning to calibrate,
```

```
not proof about your model.
```

```
- **Evaluation idea:** Build labeled pairs: true duplicates, paraphrases, same-
topic-different-fact, and near-identical but negated or with a changed number.
Then correlate similarity with actual task retention. Your claim that similarity
≠ preservation becomes testable.
```

- `**11. Hallucination, calibration, grounding**` 

```
- **Where:** §10.6 p. 219; §14 intro pp. 289-290; TyDi QA NULL answers p. 304
- **Book says:** LLMs state unfaithful things and are poorly calibrated, so
wrong answers come with confidence. It cites a legal-domain study with 69-88%
hallucination. RAG grounds answers in supplied text. TyDi QA requires a NULL
answer when no passage contains the answer.
```

```
- **For PromptOptimizer:** If optimization drops needed information, the model
may fabricate instead of failing visibly. Performance retention can look fine on
easy items while hallucination rises.
- **Evaluation idea:** Run answerability probes: deliberately remove the needed
fact and check whether the model abstains or invents. The book gives no metric
for factual consistency (see gaps).
```

# `## Useful` 

- `**12. Lossy-normalization pitfalls**` 

```
- **Where:** §2.6 p. 23 (case folding); §4.2 p. 61 and §14.1.2 p. 294 (stop
words); §4.4 p. 63 (negation)
```

```
- **Book says:** Case folding helps retrieval but hurts where case carries
meaning (US vs us). Stop-word removal usually doesn't help classification and
can wreck phrases ("to be or not to be" becomes "not"). Negation flips meaning,
and the classic fix marks words inside its scope.
```

```
- **For PromptOptimizer:** Your rule-based baseline must not delete function
words or qualifiers blindly. Protect negations, modals, quantifiers, numbers and
units.
```

```
- **Evaluation idea:** Build minimal-pair tests where one word (not, only,
except, at most) changes the right answer. They should be especially revealing
for your Category E.
```

```
**13. Adequacy vs fluency; chrF; BERTScore**
- **Where:** §13.6, pp. 280-284
- **Book says:** Judge output on adequacy (how much source information is
preserved) and fluency. Human raters are best but need training and
normalization, and post-editing is an alternative. chrF is a character n-gram F-
score, but it is local and can't assess cross-sentence coherence. It suits
comparing changes to a single system, not different systems. BERTScore matches
token embeddings by cosine to give precision, recall and F1. COMET and BLEURT
are trained on human ratings.
```

```
- **For PromptOptimizer:** "Information retention" is essentially adequacy. Your
setup (same LLM, original vs optimized input) is the use case where chrF-style
metrics are considered appropriate. Use BERTScore recall with answer A as the
reference for "did B keep A's content."
```

```
- **Evaluation idea:** Human-rate a 10-20% sample and check rank correlation
with the automatic metric. Overlap is not correctness, especially for code.
```

```
**14. Discourse structure for importance (RST)**
```

```
- **Where:** §24.1, pp. 532-535; argument structure p. 548
```

```
- **Book says:** Rhetorical Structure Theory splits text into nuclei (central,
stand-alone) and satellites (less central, interpretable only relative to a
nucleus). The book says nuclei presumably carry more important information and
satellites might be dropped in a summary (p. 535).
```

```
- **For PromptOptimizer:** This is a principled basis for prose importance,
instead of hand-set weights like 0.35 for background. It also flags what can't
be dropped alone, since a satellite whose nucleus is removed is orphaned.
- **Evaluation idea:** At equal token budget, compare drop-satellites-first,
lowest-TF-IDF-first and random-drop. The random-drop control tells you whether
any scoring helps. You'd need an LLM to approximate RST parsing.
```

```
**15. Template filling**
- **Where:** §20.8, pp. 456-458
- **Book says:** Fixed slots are filled by text spans or inferred values, in two
steps (detect the template, then extract role-fillers). Templates can nest and
are evaluated slot by slot.
```

```
- **For PromptOptimizer:** This is a concrete model for "compress a document
into a task-specific structured record." Your requirement and constraint lists
are templates already.
```

```
- **Evaluation idea:** Fill the same schema from the original and the optimized
input. Slot-level precision and recall then give an information-retention score
that doesn't rely on embeddings. The book's methods are pre-LLM taggers, so take
the concept, not the algorithms.
```

```
**16. Efficiency and cost measurement**
- **Where:** §9.1 p. 194; §10.4 p. 215; §10.5.2 p. 217; §12.5.1 p. 256
- **Book says:** Attention cost grows quadratically with input length.
Evaluation should include time, memory, energy and financial cost, ideally
normalized to a compute budget. The KV cache stores earlier tokens' keys and
values to avoid recomputation. In prompt search, scoring cost is critical.
- **For PromptOptimizer:** Measure net savings: optimizer calls plus the
downstream call. KV caching is the mechanism behind provider prompt caching, so
rewriting a stable prefix per request may forfeit cache discounts (my inference;
the book doesn't discuss API caching, so check provider docs).
- **Evaluation idea:** Compare latency and cost per request, original vs
optimized, including optimizer overhead. Find the input size at which the
optimizer breaks even.
```

- `**17. Prompt templates and instruction placement** - **Where:** §12.1, pp. 243-245` 

```
- **Book says:** A prompt is context for next-token generation. Templates have
input slots, and putting the instruction after the input tends to constrain
output. Prompts must be unambiguous so any reasonable continuation does the
task. They can specify allowed answers, length, persona or JSON.
- **For PromptOptimizer:** Your Task/Context/Requirements/Constraints
segmentation is template slots. Treat instruction placement and output-format
specs as protected elements.
```

```
- **Evaluation idea:** Measure instruction-following rate (did the model do the
task or just continue the text) before and after, with an ablation that moves
the instruction.
```

# `## Smaller items` 

```
- **Edit distance and WER** (§2.8 pp. 25-29; §16.5 p. 346). Useful and cheap.
Reconstruct the original from a "lossless" transformation and check the edit
distance is zero.
```

```
- **Perplexity and entropy** (§3.3, §3.7, §10.4). Optional. Token surprisal as a
redundancy signal is plausible, but the book doesn't use it that way. Perplexity
isn't comparable across tokenizers.
- **Coreference** (p. 501, p. 457). Optional. A cheap check for dangling
pronouns after deleting chunks.
```

```
- **Chain-of-thought** (§12.4 p. 254). Optional. Treat reasoning demos and
"think step by step" as protected.
```

```
- **Temperature and top-p sampling** (§10.2). Fix low or greedy decoding in
evaluations.
```

```
- **Text classifiers** (Ch. 4-5, §11.4). Optional. TF-IDF plus logistic
regression could implement Task Identification without an LLM call.
- **Probably unnecessary:** pretraining, scaling laws, LoRA (§10.3, 10.5.1,
10.5.3), instruction tuning, RLHF, word2vec and PMI (§6.6-6.8), and the network
internals in Ch. 7-9.
```

```
# A. Top 10 concepts for PromptOptimizer
```

```
1. Prompt optimization as search with sampled execution accuracy (§12.5)
```

`2. Paired bootstrap and dev/test discipline (§4.8-4.9)` 

`3. Precision/recall/F with macro-averaging for retention metrics (§4.7)` 

`4. Retrieve-then-rerank and the RAG framing (§14.2-14.3)` 

`5. Task metrics: exact match, token F1, MRR (§14.4)` 

`6. Few-shot findings: diminishing returns, examples teach format (§12.1.1)` 

`7. TF-IDF/BM25/cosine as the zero-cost baseline (§6.5, §14.1)` 

`8. Anisotropy: calibrate embedding-similarity thresholds (§11.3.2)` 

`9. Tokenization: measure with the target tokenizer (§2.5)` 

`10. Dataset documentation and leave-one-category-out evaluation (§2.3, §12.3, §14.3.2)` 

```
Close runners-up: lossy-normalization pitfalls, hallucination and abstention
probes, template-filling retention scores, and RST.
```

```
# B. Read in detail
```

- `**Ch. 12:** §12.1.1 (pp. 246-247) and §12.5 (pp. 255-257). About 5 pages.` 

- `**Ch. 14 in full** (pp. 289-306), especially §14.2-14.4.` 

- `**Ch. 4:** §4.7-4.9 (pp. 66-72).` 

- `**Ch. 11:** §11.3.2 (pp. 233-234).` 

- `**Ch. 13:** §13.6 (pp. 280-284).` 

- `**Ch. 2:** §2.5 (pp. 18-23), skimming §2.6.` 

```
- **Ch. 6:** §6.4-6.5 (pp. 110-113) as a refresher.
- **Ch. 10:** §10.4-10.5.2 (pp. 214-217).
- **Ch. 24:** §24.1 (pp. 532-535).
- **Ch. 20:** §20.8 (pp. 456-458).
```

```
# C. Safe to ignore
```

```
- Ch. 3 (except the perplexity/entropy ideas) and Ch. 5
- §6.6-6.12 (PMI, word2vec, embedding bias)
- Ch. 7-8 and the internals of Ch. 9
- §10.1-10.3 and §10.5.1/10.5.3
- §11.1-11.2 and §11.5
- §12.2-12.3 and §12.7
- Ch. 13 (except §13.6)
- Ch. 15-19, 21 and 22
```

```
- The algorithms in Ch. 23 and §24.2-24.4
```

# `# D. Gaps where you'll need other sources` 

```
The pointers below come from my own knowledge, not the book. Search for current
versions, since this area moves fast.
```

```
1. **Prompt and context compression itself.** The book has no token pruning,
learned compression or summarization-based compression. Look at LLMLingua and
Selective Context and their successors.
```

```
2. **LLM-as-judge and factual-consistency evaluation.** The book has neither.
Look at MT-Bench-style judging, G-Eval, RAGAS, FActScore, and NLI-based claim
checking.
```

```
3. **Long-context behavior.** The book says nothing about position effects or
degradation as input grows ("lost in the middle," needle-in-a-haystack).
```

```
4. **Chunking beyond fixed ~100-token windows.** Nothing on structure-aware
chunking, and nothing on code.
```

```
5. **Sentence-embedding practice.** The book covers BERT-style encoders and the
anisotropy problem, but not modern embedding models, threshold selection or
near-duplicate detection.
```

```
6. **Structured output and format effects.** JSON gets one passing mention (p.
245). It doesn't cover Markdown vs JSON vs YAML performance, schema-constrained
decoding or format token costs.
```

```
7. **Code and tabular evaluation.** Your Categories A and C have no coverage.
Look at execution-based tests and pass@k for code, and schema and aggregate
checks for CSV and JSON.
```

```
8. **Evaluating stochastic LLMs.** The book has nothing on variance across
samples, repeated runs, benchmark contamination, or evaluating prompts on models
```

```
that change under you.
9. **Learning importance directly.** The book has no ablation, attribution or
Shapley-style importance estimation. That is the heart of your Importance(i |
Task) problem.
```

```
10. **Information-preservation theory.** Entropy appears only as a bits lower
bound. Rate-distortion and information-bottleneck framings would back your
```

```
"minimum sufficient information" idea.
```

```
11. **Provider-side cost mechanics.** The book has no prompt caching, batching
or pricing, only the conceptual KV cache.
```

