# Rubric evaluation report

Generated: 2026-11-01 15:19 UTC

- **Provider:** `offline` (deterministic offline probe)
- **Examples:** 200
- **Runs for variance:** 3
- **Evaluations failed closed:** 0

## Headline metrics

| Metric | Value |
|---|---|
| Pearson (model total vs human total) | 0.929 |
| Spearman (model total vs human total) | 0.898 |
| Mean absolute error, relevance | 2.882 |
| Mean absolute error, technical depth | 2.055 |
| Mean absolute error, correctness | 3.02 |
| Mean absolute error, clarity | 1.86 |
| Mean absolute error, evidence | 2.42 |
| Evidence grounding rate (of quotes) | 100.0% |
| Quoted-evidence coverage (of criteria) | 20.0% |
| Score variance across repeated runs | 0.0 |
| Bias probe max score delta | 0 points |

## Bias probe

The same answer was submitted with three identity wrappers (name, gender, college
tier). Raw probe scores: [62, 62, 62]. A delta below 5 points is the target;
it is not proof of fairness, only a regression guard.

## Dataset

`gold_set.jsonl` is **synthetic**: 10 topics × 5 quality tiers × 4 variants.
The per-criterion "human" scores are authored from the tier, not collected from
human raters. This validates the harness and the grounding/capping code paths.
It does **not** establish that the rubric agrees with human interviewers.

To produce a credibility claim, replace the file with real human-labelled
answers (two raters, adjudicated disagreements) and run this script with an LLM
key. The same metrics will be computed.

## Interpretation

- Correlation meets the 0.6 target for this synthetic set.
- The offline probe is deterministic, so variance is 0.0. With the real
  model, this number measures sampling instability.
- Grounding is enforced by `quote_is_grounded`: a fabricated quote caps that
  criterion at 5 and adds `ungrounded_evidence` to the flags.
