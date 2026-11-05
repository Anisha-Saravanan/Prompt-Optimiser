# PromptOptimizer — Project Knowledge Base

*Concepts, formulas and design decisions needed to build and evaluate an information-preserving prompt optimizer. Built from your project brief and the uploaded pages of Speech and Language Processing (SLP3).*

**How to read this doc:** sections marked **\[Book\]** summarise material from the uploaded SLP3 pages (book page numbers given). Sections marked **\[Design\]** are my proposals for applying that material to PromptOptimizer. They are suggestions to test, not established results.

## 1. Project in one paragraph

PromptOptimizer is a Java/Spring Boot system that shrinks prompts and attached files (code, PDFs, CSV, JSON, text) into a more compact, structured form **without losing information the downstream LLM needs for the task**. It is task-aware, file-aware, representation-aware and evaluation-driven. The central question: *how much input can be removed or transformed while the downstream AI still solves the task correctly?* Token reduction, information preservation and task performance must always be reported together.

## 2. Concept map: what you need from the book

| Project need | Concept | Book location |
| --- | --- | --- |
| Measure token cost (L\_i) | Tokenization, subword tokens, BPE | Ch. 2, pp. 15–23 |
| Decide what to normalise or not | Case folding, lemmatization, stemming | Ch. 2, p. 23 |
| Detect redundancy | TF-IDF, cosine similarity | Ch. 6, pp. 110–114 |
| Semantic redundancy | Contextual embeddings, anisotropy | Ch. 11, pp. 233–234 |
| Split input into information units | Discourse coherence, RST (nucleus/satellite) | Ch. 24, pp. 533–536 |
| Understand the downstream prompt | Prompting, few-shot, instruction tuning | Ch. 12, pp. 243–254 |
| Select relevant context for a task | Information retrieval, dense retrieval, RAG | Ch. 14, pp. 291–305 |
| Requirement / constraint recall | Precision, recall, F-measure | Ch. 4, pp. 66–68 |
| Compare strategies rigorously | Paired bootstrap, p-values | Ch. 4, pp. 70–72 |
| Compare original vs optimized answers | chrF, BLEU, BERTScore, COMET, human rating | Ch. 13, pp. 280–284 |
| Score task answers | QA metrics, MRR, multiple-choice accuracy | Ch. 14, pp. 295–305 |

## 3. Tokens and token cost  \[Book: Ch. 2\]

**What the book says**

- Tokenization comes in two broad families. *Top-down* tokenization applies hand-written rules (typically regular expressions compiled to fast automata). *Bottom-up* tokenization learns a vocabulary of subword tokens from letter-sequence statistics.
- Rule-based tokenizers must handle punctuation inside words (m.p.h., Ph.D.), prices ($45.55), dates, URLs, hashtags and emails. Number formats differ by language (555,500.50 versus 555 500,50). The Penn Treebank standard splits clitics (doesn't → does + n't) and separates punctuation.
- **Byte-Pair Encoding (BPE)** starts with single characters as the vocabulary, then repeats k times: find the most frequent adjacent pair, merge it into a new token, add it to the vocabulary, and replace every occurrence in the corpus. After many merges, common words become single tokens and rare or unknown words are split into pieces (for example *lower* → *low* + *er*).

**Why it matters here**

- Token cost is not word count. Rare identifiers, code symbols, numbers and non-English text split into many subword tokens, so a short-looking string can be expensive.
- Token counts depend on the tokenizer. Always count with the tokenizer of the target model, and state which one you used when reporting Token Reduction.
- **\[Design\]** Compute L\_i per information unit with the target model's tokenizer, not with whitespace splitting.

## 4. Normalization: what is safe to change  \[Book: Ch. 2.6\]

- **Case folding** helps generalisation for retrieval and speech recognition, but is generally *not* done for sentiment analysis, information extraction or translation, because case carries meaning (the book's example is *US* the country versus *us* the pronoun).
- **Normalising variants** (USA/US, uh-huh/uhhuh) can help retrieval but discards spelling information.
- **Lemmatization and stemming** make morphological variants behave alike (woodchucks/woodchuck).
- **\[Design\]** Treat normalization as a *lossy* transformation. Safe candidates for natural-language prose: whitespace cleanup, boilerplate removal, greeting removal. Unsafe for code, logs, error messages, identifiers and exact strings, where case and spelling are the information. Record every normalization applied so it appears in the Transformation Type field of the dashboard.

## 5. Redundancy detection: TF-IDF and cosine  \[Book: Ch. 6\]

**Cosine similarity** between vectors v and w:

`cosine(v, w) = (v · w) / (|v| |w|)`

- Ranges from 1 (same direction) through 0 (orthogonal) to −1. For non-negative count vectors the range is 0–1.
- If vectors are pre-normalised to unit length, the dot product equals the cosine.
- The book's example: with counts over (pie, data, computer), cos(cherry, information) ≈ .018 while cos(digital, information) ≈ .996.

**TF-IDF** weights a term by how discriminative it is:

- `idf_t = log10(N / df_t)` where N is the number of documents and df\_t is the number of documents containing term t.
- `w(t,d) = tf(t,d) × idf_t`
- Terms appearing in every document get idf 0 (in the book's Shakespeare table, *good* and *sweet* appear in all 37 plays and score 0; *Romeo*, in one play, scores 1.57).
- You must choose what counts as a document. If the corpus has no natural divisions, you create them.

**\[Design\] Applying this to PromptOptimizer**

- Treat each information unit (a paragraph, requirement, code block, CSV schema description) as a "document" and the full input as the collection.
- Units whose terms are common across the whole input and that have high cosine similarity to an earlier unit are redundancy candidates (R\_i).
- TF-IDF is a cheap, deterministic baseline, which fits Strategy 1/2 in your brief.
- **Caution:** lexical overlap misses paraphrases. "Do not rewrite working modules" and "The program should not modify existing modules" share few words but mean the same, which is why you also need embeddings (next section).

## 6. Embeddings for semantic redundancy  \[Book: Ch. 11\]

- Contextual embeddings from masked language models and autoregressive models are **anisotropic**: vectors for unrelated words can have cosines close to 1 because they all point in roughly the same direction. One cause is a few "rogue" dimensions with very large magnitude and variance.
- **Fix from the book:** standardise (z-score) the embeddings across a corpus. Compute the mean vector μ and per-dimension standard deviation σ over all embeddings, then replace each x with z = (x − μ) / σ.
- Even after standardisation, cosine tends to underestimate human judgments of similarity for similar items.

**\[Design\]**

- Do not use a raw cosine threshold such as 0.9 without calibrating it on your own data. Standardise embeddings over the units of the current input (or a reference corpus) first.
- Calibrate the "duplicate" threshold per task category using hand-labelled pairs from your dataset.
- Semantic similarity can flag duplicates, but the brief is right that it is **not proof** of task-performance preservation. Two units can be similar and still differ in one critical number or negation.

## 7. Splitting input into information units  \[Book: Ch. 24\]

- A text is coherent when its parts connect through **coherence relations** (the book's contrast: "Jane took a train from Paris to Istanbul. She had to attend a conference." is coherent because the second sentence gives a reason; "She likes spinach." is not).
- In **Rhetorical Structure Theory (RST)**, relations hold between a **nucleus** (central to the writer's purpose, interpretable on its own) and a **satellite** (less central, interpretable mainly in relation to the nucleus). Some relations are multinuclear (e.g. *List*, where several nuclei are equal). Examples include evidence, attribution, background, elaboration, contrast, purpose and explanation.
- Relations nest hierarchically, forming a tree over a paragraph.
- The book notes that knowing coherence relations between sentences helps summarisation decide what information to select.

**\[Design\]**

- Use nucleus/satellite as a *prior* for importance: nuclei (the task, hard constraints) start high; satellites (background, elaboration, evidence) start lower and are compressed first. Multinuclear lists (for example a list of requirements) should be treated as a set where every item matters.
- **Segment before scoring.** Suggested unit types for your Information Segmentation stage: Task, Requirement, Constraint, Example, Context/Background, Code, Documentation, Data schema, Repeated information.
- **Do not break a unit away from the unit that makes it interpretable.** A satellite removed from its nucleus loses meaning; a nucleus kept without needed references may become ambiguous. Keep dependency links between units (for example a constraint that refers to "the module above").

## 8. How downstream prompts work  \[Book: Ch. 12\]

- A **prompt** is a text string given to a language model; the model generates tokens conditioned on it. **Prompt engineering** is finding prompts that work for a task.
- A prompt can include a task definition, input, and **demonstrations** (labelled examples). Prompting with examples is **few-shot**; without them it is **zero-shot**.
- Few-shot gains: a small number of examples can be enough, and the largest gain typically comes from the **first** example, with diminishing returns afterwards.
- **Instruction tuning** (finetuning on instruction-and-response data) is what makes models follow task instructions in prompts. The book also discusses alignment concerns such as toxicity and harm.

**\[Design\]**

- Examples are a prime compression target: keep the most informative one or two, and test dropping the rest. Your brief gives Example an importance of about 0.6, and the book's diminishing-returns observation supports pruning, but you should measure this per task type.
- Preserve the **structure** of the prompt (task definition → context → examples → query). Moving the instruction away from the end, or merging it into background text, can change behaviour. Test representation changes, not only deletions.

## 9. Retrieval and RAG as a design template  \[Book: Ch. 14\]

- **Information retrieval** ranks documents by similarity to a query. Sparse methods use TF-IDF vectors and an inverted index; dense methods use encoder embeddings (bi-encoders, or ColBERT-style token-level matching) and approximate nearest-neighbour search (for example Faiss).
- **RAG** answers a question by retrieving passages and placing them in the prompt followed by an instruction, as in: passages 1..n, "Based on these texts, answer this question: Q: … A:". Practical details include reranking, multi-hop retrieval, and whether to mark passage boundaries with separators.
- The book flags **privacy concerns** when private data is combined with an externally hosted LLM.

**\[Design\]** PromptOptimizer is conceptually a *task-conditional retriever over the user's own input*: the "query" is the identified task (plus requirements), and the "documents" are information units. That gives you ready-made ideas:

- Score `Importance(unit | task)` as similarity between unit and task representation (sparse, dense, or hybrid), then rerank with an LLM.
- Use passage-boundary markers in the optimized prompt so units stay separable.
- Because your system sends content to an external LLM, add a privacy note and an option to redact sensitive fields before sending.

## 10. Evaluation toolkit  \[Book: Ch. 4, 13, 14\]

### 10.1 Precision, recall, F-measure

- `Precision = TP / (TP + FP)`
- `Recall = TP / (TP + FN)`
- `F_β = (β² + 1) P R / (β² P + R)`; `F1 = 2PR / (P + R)`
- β > 1 favours recall; β < 1 favours precision. F is a weighted **harmonic mean**, which stays closer to the lower value and so is more conservative than an arithmetic mean.
- Accuracy can be misleading on imbalanced data: the book's "nothing is pie" classifier has 99.99% accuracy but recall 0.
- **Macro-averaging** computes the metric per class then averages (good when every class matters equally); **micro-averaging** pools all decisions (dominated by the frequent class).

**\[Design\] Mapping to your metrics**

- *Requirement Recall* = preserved requirements / original requirements. It is a recall measure, so also track **precision** (did the optimizer introduce requirements that were not in the original?) and report F\_β.
- Because losing one hard constraint can change the answer entirely, use a recall-weighted score (β > 1, for example β = 2) for constraints.
- Average across task categories with **macro-averaging** so small categories (such as Complex Multi-Constraint) are not swamped by large ones; report micro-averages as well.

### 10.2 Comparing original and optimized answers

- **Human evaluation** is the most reliable but slow and costly. For translation the book describes rating *adequacy* (how much information was preserved) and *fluency*, ranking pairs, and post-editing. It stresses rater training, expects disagreement between raters, and normalises scores per rater.
- **Overlap metrics** (chrF at the character level, BLEU at the word n-gram level) are cheap but very local, and BLEU is sensitive to tokenization. They are meant for comparing systems, not as absolute truth.
- **Embedding metrics** such as BERTScore score token pairs by cosine similarity of contextual embeddings and need no training data. **COMET** and **BLEURT** instead train a predictor on human-rated examples, and correlate highly with human judgments.

**\[Design\]** "Adequacy" is exactly your question (was the information preserved?). Suggested answer-comparison stack, cheapest to most reliable:

1. Exact-match or schema validity for structured tasks.
2. Embedding similarity between Answer A (original) and Answer B (optimized), reported alongside, never instead of, task checks.
3. Task-specific checks (does the fix compile and pass tests? Are extracted fields correct?).
4. LLM or human judging against the requirement checklist, spot-checked by a human subset.

### 10.3 QA and ranking metrics

- Multiple-choice benchmarks (such as MMLU) are scored by accuracy.
- **Mean reciprocal rank (MRR)**: for each question take 1 / rank of the first correct answer, then average.
- Retrieval uses precision and recall at each rank cut-off and interpolated precision-recall curves.
- **\[Design\]** If your importance estimator ranks units, evaluate that ranking: are the units a human marked essential ranked near the top (precision@k, recall@k, MRR)?

### 10.4 Statistical significance

- Goal: decide whether strategy A is really better than B, or whether the observed difference δ(x) on the test set could be chance. The null hypothesis H0 says A is not better than B. The **p-value** is the probability of seeing a difference at least as large as δ(x) if H0 were true; below a threshold (commonly .05 or .01) you reject H0 and call the result statistically significant.
- NLP typically uses **non-parametric** tests based on resampling, because t-tests and ANOVA assume distributions that rarely hold.
- **Paired bootstrap test:** create a large number b of pseudo test sets (for example 10⁵) by sampling n items *with replacement* from the real test set, compute the metric difference on each, and see how often the difference is as large as the one observed.
- **Confidence intervals:** bootstrap the metric, drop the top and bottom 2.5% of scores, and the rest give a 95% interval.

**\[Design\]** Run paired bootstrap on per-example scores when comparing Rule-based vs Embedding vs LLM vs Hybrid vs Task-specific. Pair the strategies on the *same* dataset items. Report confidence intervals for Token Reduction and Performance Retention, not just means.

## 11. Metric definitions for the project

| Metric | Definition |
| --- | --- |
| Token Reduction % | (Original tokens − Optimized tokens) / Original tokens × 100 |
| Remaining Token Ratio | Optimized tokens / Original tokens |
| Performance Retention % | Optimized-answer score / Original-answer score × 100 |
| Requirement Recall | Preserved requirements / Original requirements |
| Constraint Recall | Preserved constraints / Original constraints |
| Information Retention | Weighted share of essential facts, requirements and constraints retained |
| Semantic Similarity | Cosine of (standardised) embeddings of original vs optimized |
| Optimization Efficiency | Performance Retention / Remaining Token Ratio |
| Utility per unit | U\_i = I\_i / L\_i (importance per token) |

**Worked example from your brief:** 8,000 → 4,500 tokens is 43.75% reduction (remaining ratio 0.5625). Quality 92 → 90 gives 97.8% retention. Efficiency ≈ 0.978 / 0.5625 ≈ **1.74**.

**Pitfalls**

- Efficiency can look good when the remaining ratio is tiny but performance silently collapsed on a few items. Always show it with the individual metrics, as your brief says.
- Ratios of averages and averages of ratios differ. Decide which one you report and apply it consistently.
- Performance Retention above 100% is possible (optimized input sometimes helps); keep it uncapped in analysis but flag it.

## 12. Importance and utility model  \[Design\]

From your brief, for information unit i given task T:

- `I_i = Importance(i | T)` (task-conditional; never a universal constant)
- `L_i` = token cost, `R_i` = redundancy
- `Utility_i = α·Importance_i + β·TaskRelevance_i + γ·Uniqueness_i − δ·Redundancy_i − ε·TokenCost_i`

**Points to settle experimentally**

1. **Hard-keep rules override the score.** Task statement, explicit hard constraints, exact error messages and required inputs are never dropped, whatever U\_i says. This makes the "minimum information-retention requirement" in your brief enforceable.
2. **Weights (α…ε) per task category**, fitted or tuned on a development split, not on the test split.
3. **Selection as a budgeted problem:** choose units to maximise total importance subject to a token budget (a knapsack-style problem) *and* the hard-keep rules, then verify with the retention check.
4. **Representation choice** (plain text → Markdown, PDF → structured Markdown, verbose JSON → compact form) is a separate decision from deletion, and must be evaluated by the same downstream task test.

## 13. Experimental protocol

1. **Dataset.** Five categories (Coding, Documents, Data, Research, Complex Multi-Constraint). Each instance: id, category, prompt, files, task type, requirements, constraints, expected output characteristics, reference answer or evaluation criteria (schema in your brief). Document how the data was collected, who labelled requirements and constraints, and any copyright or licensing limits on included files; the book's corpus guidance recommends recording this kind of provenance.
2. **Splits.** Development set for tuning weights/thresholds; held-out test set for final numbers.
3. **Per item:** run Original → LLM → Reference result; run each strategy's Optimized → LLM → Result.
4. **Compute** all metrics in Section 11, per item.
5. **Aggregate** per category (macro) and overall; add bootstrap confidence intervals.
6. **Compare strategies** with the paired bootstrap; test both hypotheses in your brief (task-aware beats generic rewriting at equal performance; best strategy depends on task type and modality).
7. **Ablations:** no hard-keep rules, no redundancy removal, no representation change, no revise loop.
8. **Baselines:** (a) original input, (b) generic "make it shorter" rewrite by an LLM, (c) rule-based only.
9. **Repeat LLM calls** where the model is stochastic and report variance, so that a difference between Answer A and Answer B is not just sampling noise.

## 14. Architecture and Spring Boot module map  \[Design\]

| Pipeline stage (from brief) | Suggested module | Notes |
| --- | --- | --- |
| Input Manager | `InputController`, `InputService` | Multipart upload, file-type detection, size limits |
| Prompt Analyzer / File Analyzer | `PromptAnalyzer`, `FileAnalyzer` (per-type parsers) | Java source, PDF text, CSV/JSON schema extraction |
| Task Identification | `TaskClassifier` | LLM or classifier; outputs task type, requirements, constraints |
| Information Segmentation | `Segmenter` | Unit types from Section 7 |
| Importance Estimation | `ImportanceEstimator` (strategy interface) | Rule, TF-IDF, embedding, LLM, hybrid, task-specific implementations |
| Redundancy Detection | `RedundancyDetector` | TF-IDF cosine plus standardised embeddings |
| Representation Selection | `RepresentationSelector` | Lossless vs lossy choice per unit/file |
| Adaptive Optimization | `Optimizer` | Budgeted selection with hard-keep rules |
| Quality/Retention Check | `RetentionChecker` | Requirement/constraint recall; accept or revise loop |
| External LLM | `LlmClient` | Provider-agnostic; logs token usage |
| Evaluation Engine | `EvaluationService` | Metrics, bootstrap, result storage |
| Dashboard | REST API plus UI | Fields listed in your brief |

Use a **strategy interface** for `ImportanceEstimator` so the experiments in Section 13 can swap implementations without touching the pipeline. Store every run (inputs, units, scores, outputs, metrics, model and tokenizer versions) so results are reproducible.

## 15. Risks and open questions

- **Tokenizer mismatch:** counts differ by model; fix one target model per experiment.
- **Lossless versus lossy:** label every transformation, and keep a mapping back to the original so a human can audit what changed.
- **Evaluator bias:** if an LLM both optimizes and judges, results may be inflated. Use a different model for judging where possible, and validate against human labels on a sample.
- **Embedding thresholds** do not transfer across models or domains (Section 6).
- **Small test sets:** with few items per category, bootstrap intervals will be wide; plan dataset size accordingly.
- **Privacy** when sending files to an external LLM (Section 9).

## 16. Glossary

- **Token:** unit of text the model processes; with BPE, often a word piece.
- **BPE:** algorithm that builds a subword vocabulary by repeatedly merging the most frequent adjacent pair.
- **Case folding:** lowercasing to treat variants alike.
- **TF-IDF:** term frequency multiplied by inverse document frequency; high for terms frequent in a document but rare overall.
- **Cosine similarity:** normalised dot product between vectors.
- **Anisotropy:** embeddings pointing in similar directions, inflating cosines.
- **Nucleus / satellite:** central versus supporting span in RST.
- **Zero-shot / few-shot:** prompt without / with demonstrations.
- **RAG:** retrieval-augmented generation.
- **Precision / recall / F-measure:** exactness, completeness, and their harmonic combination.
- **Paired bootstrap:** resampling test for whether system A truly beats B.
- **MRR:** mean reciprocal rank of the first correct answer.

## 17. Source index (uploaded PDF pages)

- PDF pp. 1–9: Ch. 2 corpora, tokenization, BPE, normalization.
- PDF pp. 10–17: Ch. 4 evaluation metrics, test sets, significance testing, harms in classification.
- PDF pp. 18–22: Ch. 6 vectors, TF-IDF, cosine.
- PDF pp. 23–24: Ch. 11 contextual embeddings and anisotropy.
- PDF pp. 25–36: Ch. 12 prompting, few-shot, instruction tuning.
- PDF pp. 37–41: Ch. 13 MT evaluation (chrF, BLEU, BERTScore, COMET).
- PDF pp. 42–56: Ch. 14 information retrieval, dense retrieval, RAG, QA evaluation.
- PDF pp. 57–60: Ch. 24 coherence relations and RST.

*Note: I read the pages most relevant to each topic closely and skimmed others (for example the corpus datasheet discussion in Ch. 2 and the harms discussion in Ch. 4), so check those book pages directly before citing them.*
