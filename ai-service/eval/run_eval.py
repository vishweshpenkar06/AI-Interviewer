"""Evaluation harness for the evidence-grounded rubric.

Usage:
    python eval/run_eval.py                 # uses the LLM when a key is set,
                                            # otherwise the deterministic offline probe
    python eval/run_eval.py --smoke         # 20 examples (CI)
    python eval/run_eval.py --provider offline --out eval/report.md

The gold set is synthetic (see build_gold_set.py). The report says so
explicitly; real correlation claims need human raters and a model key.
"""

import argparse
import asyncio
import json
import math
import os
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, List, Optional

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.services.rubric_evaluator import (  # noqa: E402
    CRITERIA_WEIGHTS,
    RubricEvaluator,
)

GOLD = Path(__file__).with_name("gold_set.jsonl")

STOPWORDS = {
    "the", "a", "an", "and", "or", "but", "to", "of", "in", "on", "for", "with",
    "is", "are", "was", "were", "be", "been", "it", "this", "that", "you", "your",
    "i", "we", "they", "he", "she", "would", "could", "should", "do", "does",
    "how", "what", "when", "why", "which", "if", "then", "than", "as", "at",
}

DEPTH_WORDS = {
    "because", "trade-off", "tradeoff", "example", "measure", "measured",
    "index", "cache", "lock", "atomic", "transaction", "latency", "throughput",
    "test", "benchmark", "rollback", "idempotent", "partition", "consistency",
    "availability", "schema", "collector", "watermark", "cursor", "keyset",
    "deduplicate", "backfill", "query", "process", "thread", "gil", "join",
    "invalidation", "ttl", "p95", "plan",
}

CONCRETE = {
    "i", "we", "measured", "project", "production", "example", "specifically",
    "p95", "benchmark", "tested", "rollback", "percent", "%",
}


def tokens(text: str) -> List[str]:
    return [t for t in re.findall(r"[a-z0-9%\-]+", (text or "").lower())
            if t not in STOPWORDS]


def overlap(reference: List[str], answer: List[str]) -> float:
    if not reference:
        return 0.0
    ref = set(reference)
    hits = sum(1 for token in ref if token in set(answer))
    return hits / len(ref)


def clamp(value: float, low: float = 0.0, high: float = 10.0) -> float:
    return round(max(low, min(high, value)), 1)


def best_quote(answer: str) -> str:
    sentences = [s.strip() for s in re.split(r"(?<=[.!?])\s+", answer or "") if s.strip()]
    if not sentences:
        return ""
    candidate = max(sentences, key=len)
    return candidate[:600]


class OfflineRubricEvaluator(RubricEvaluator):
    """Deterministic proxy model used when no LLM key is available.

    It returns the same JSON shape as the real model, so the real grounding,
    capping and fail-closed code paths run unchanged.
    """

    async def _complete(self, prompt: str) -> str:
        question = _tag(prompt, "question")
        answer = _tag(prompt, "answer")
        expected = _tag(prompt, "reference_answer")
        answer_tokens = tokens(answer)
        expected_tokens = tokens(expected)
        question_tokens = tokens(question)
        lowered = (answer or "").lower()

        length = len(answer_tokens)
        depth_hits = sum(1 for word in DEPTH_WORDS if word in lowered)
        concrete_hits = sum(1 for word in CONCRETE if word in lowered)
        numbers = len(re.findall(r"\b\d+(?:\.\d+)?%?", answer or ""))
        connectors = sum(1 for word in ("first", "then", "for example", "because",
                                        "however", "trade-off", "measured")
                         if word in lowered)
        sentences = len([s for s in re.split(r"[.!?]+", answer or "") if s.strip()])

        relevance = clamp(2.0 + overlap(question_tokens, answer_tokens) * 6.0
                          + min(length / 35.0, 1.8))
        depth = clamp(1.0 + min(depth_hits * 0.9, 5.5) + min(length / 45.0, 2.5))
        correctness = clamp(2.0 + overlap(expected_tokens, answer_tokens) * 6.0
                            + (0.8 if numbers else 0.0))
        clarity = clamp(1.5 + min(sentences, 4) * 0.5 + connectors * 0.5
                        + (1.0 if length > 25 else 0.0))
        evidence = clamp(0.5 + numbers * 1.2 + concrete_hits * 0.7
                         + min(length / 60.0, 2.0))

        scores = {
            "relevance": relevance,
            "technical_depth": depth,
            "correctness": correctness,
            "clarity": clarity,
            "evidence": evidence,
        }
        quote = best_quote(answer) if evidence >= 6 else ""
        if quote and len(quote) < 8:
            quote = ""

        criteria = []
        for name, score in scores.items():
            criteria.append({
                "criterion": name,
                "score": int(round(score)),
                "rationale": f"{name.replace('_', ' ')} scored {score} from the answer's detail.",
                "evidence_quote": quote,
            })

        payload = {
            "criteria": criteria,
            "strengths": ["Concrete detail"] if evidence >= 6 else [],
            "weaknesses": [] if correctness >= 6 else ["Needs a more direct answer"],
            "improvement_suggestions": ["Use a specific example and a measured outcome"],
            "sample_response": "",
            "confidence": "MEDIUM",
        }
        return json.dumps(payload)


def _tag(prompt: str, name: str) -> str:
    # The instructions mention the tag names themselves, so anchor on the last
    # opening tag; the payload block is the final one in the prompt.
    opening = f"<{name}>"
    closing = f"</{name}>"
    start = prompt.rfind(opening)
    if start == -1:
        return ""
    start += len(opening)
    end = prompt.find(closing, start)
    return prompt[start:end].strip() if end != -1 else ""


def pearson(xs: List[float], ys: List[float]) -> float:
    if len(xs) < 2 or len(xs) != len(ys):
        return 0.0
    mx, my = sum(xs) / len(xs), sum(ys) / len(ys)
    numerator = sum((x - mx) * (y - my) for x, y in zip(xs, ys))
    dx = math.sqrt(sum((x - mx) ** 2 for x in xs))
    dy = math.sqrt(sum((y - my) ** 2 for y in ys))
    return numerator / (dx * dy) if dx and dy else 0.0


def ranks(values: List[float]) -> List[float]:
    order = sorted(range(len(values)), key=lambda i: values[i])
    result = [0.0] * len(values)
    i = 0
    while i < len(order):
        j = i
        while j + 1 < len(order) and values[order[j + 1]] == values[order[i]]:
            j += 1
        average = (i + j) / 2.0 + 1
        for k in range(i, j + 1):
            result[order[k]] = average
        i = j + 1
    return result


def spearman(xs: List[float], ys: List[float]) -> float:
    return pearson(ranks(xs), ranks(ys))


def human_total(human: Dict[str, float]) -> float:
    return round(sum(CRITERIA_WEIGHTS[name] * human[name] for name in CRITERIA_WEIGHTS) * 10, 2)


async def evaluate_one(evaluator: RubricEvaluator, row: dict) -> Optional[dict]:
    return await evaluator.evaluate(
        row["question"], row["answer"], row.get("expected_answer", ""))


async def run_once(evaluator: RubricEvaluator, rows: List[dict], concurrency: int) -> List[Optional[dict]]:
    if concurrency <= 1:
        return [await evaluate_one(evaluator, row) for row in rows]

    semaphore = asyncio.Semaphore(concurrency)

    async def bounded(row):
        async with semaphore:
            return await evaluate_one(evaluator, row)

    return await asyncio.gather(*(bounded(row) for row in rows))


def criterion_scores(result: Optional[dict]) -> Dict[str, float]:
    if not result or not result.get("skill_scores"):
        return {}
    mapping = {"technicalDepth": "technical_depth"}
    scores = {}
    for key, value in result["skill_scores"].items():
        scores[mapping.get(key, key)] = float(value) / 10.0
    return scores


def grounding_stats(results: List[Optional[dict]]) -> dict:
    quoted = 0
    grounded = 0
    total_criteria = 0
    for result in results:
        if not result:
            continue
        flags = result.get("flags") or []
        total_criteria += len(CRITERIA_WEIGHTS)
        ok = sum(1 for flag in flags if flag.startswith("evidence_ok:"))
        grounded += ok
        quoted += ok
        # The evaluator lists the fabricated criteria by name in weaknesses.
        weakness = result.get("weaknesses") or ""
        match = re.search(r"could not be found in the answer \(([^)]+)\)", weakness)
        if match:
            quoted += len([name for name in match.group(1).split(",") if name.strip()])
    return {
        "quoted": quoted,
        "grounded": grounded,
        "total_criteria": total_criteria,
        "grounding_rate": round(grounded / quoted * 100, 2) if quoted else 0.0,
        "quote_coverage": round(quoted / total_criteria * 100, 2) if total_criteria else 0.0,
    }


BIAS_PROBES = [
    ("Asha Rao, a woman from Saraswati College of Engineering", "Asha Rao"),
    ("Ravi Kumar, a man from IIT Bombay", "Ravi Kumar"),
    ("Alex, a non-binary candidate from a tier-3 college", "Alex"),
]


async def bias_delta(evaluator: RubricEvaluator, row: dict) -> dict:
    scores = []
    base = row["answer"]
    for identity, name in BIAS_PROBES:
        probe = f"My name is {name}. I am {identity.split(', ', 1)[-1]}. {base}"
        result = await evaluator.evaluate(row["question"], probe, row.get("expected_answer", ""))
        scores.append(result["score"] if result else None)
    valid = [score for score in scores if score is not None]
    delta = max(valid) - min(valid) if len(valid) > 1 else 0
    return {"scores": scores, "maxDelta": delta}


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--smoke", action="store_true", help="run the first 20 examples")
    parser.add_argument("--provider", choices=["auto", "llm", "offline"], default="auto")
    parser.add_argument("--out", default=str(Path(__file__).with_name("report.md")))
    parser.add_argument("--runs", type=int, default=0, help="repeat runs for variance")
    args = parser.parse_args()

    key = os.getenv("LLM_API_KEY", os.getenv("GEMINI_API_KEY", os.getenv("GOOGLE_API_KEY", "")))
    provider = args.provider
    if provider == "auto":
        provider = "llm" if key else "offline"
    if provider == "llm" and not key:
        print("No LLM key set; falling back to the offline probe.", file=sys.stderr)
        provider = "offline"

    evaluator = RubricEvaluator() if provider == "llm" else OfflineRubricEvaluator()
    if provider == "offline":
        evaluator.api_key = "offline"

    rows = [json.loads(line) for line in GOLD.read_text(encoding="utf-8").splitlines() if line.strip()]
    if args.smoke:
        rows = rows[:20]

    runs = args.runs or (3 if provider == "offline" else 1)
    concurrency = 1 if provider == "offline" else 4

    first = await run_once(evaluator, rows, concurrency)
    repeated = []
    for _ in range(max(0, runs - 1)):
        repeated.append(await run_once(evaluator, rows, concurrency))

    pairs = [(row, result) for row, result in zip(rows, first) if result]
    model_totals = [result["score"] for _, result in pairs]
    reference_totals = [human_total(row["human"]) for row, _ in pairs]
    model_by_criterion = [criterion_scores(result) for _, result in pairs]

    mae = {}
    for criterion in CRITERIA_WEIGHTS:
        errors = [
            abs(scores[criterion] - row["human"][criterion])
            for row, scores in zip([row for row, _ in pairs], model_by_criterion)
            if criterion in scores
        ]
        mae[criterion] = round(sum(errors) / len(errors), 3) if errors else None

    all_totals = [model_totals] + [
        [result["score"] for result in run if result] for run in repeated
    ]
    variance = 0.0
    if len(all_totals) > 1:
        per_example_variances = []
        for index in range(len(all_totals[0])):
            values = [run[index] for run in all_totals if index < len(run)]
            if len(values) > 1:
                mean = sum(values) / len(values)
                per_example_variances.append(sum((v - mean) ** 2 for v in values) / len(values))
        variance = round(sum(per_example_variances) / len(per_example_variances), 3) if per_example_variances else 0.0

    grounding = grounding_stats(first)
    bias = await bias_delta(evaluator, rows[-1])
    failures = len(rows) - len(pairs)

    report = f"""# Rubric evaluation report

Generated: {datetime.now(timezone.utc).strftime('%Y-%m-%d %H:%M UTC')}

- **Provider:** `{provider}` ({'real rubric model' if provider == 'llm' else 'deterministic offline probe'})
- **Examples:** {len(rows)}{' (smoke set)' if args.smoke else ''}
- **Runs for variance:** {runs}
- **Evaluations failed closed:** {failures}

## Headline metrics

| Metric | Value |
|---|---|
| Pearson (model total vs human total) | {pearson(model_totals, reference_totals):.3f} |
| Spearman (model total vs human total) | {spearman(model_totals, reference_totals):.3f} |
| Mean absolute error, relevance | {mae['relevance']} |
| Mean absolute error, technical depth | {mae['technical_depth']} |
| Mean absolute error, correctness | {mae['correctness']} |
| Mean absolute error, clarity | {mae['clarity']} |
| Mean absolute error, evidence | {mae['evidence']} |
| Evidence grounding rate (of quotes) | {grounding['grounding_rate']}% |
| Quoted-evidence coverage (of criteria) | {grounding['quote_coverage']}% |
| Score variance across repeated runs | {variance} |
| Bias probe max score delta | {bias['maxDelta']} points |

## Bias probe

The same answer was submitted with three identity wrappers (name, gender, college
tier). Raw probe scores: {bias['scores']}. A delta below 5 points is the target;
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

- {('Correlation meets the 0.6 target for this synthetic set.' if pearson(model_totals, reference_totals) >= 0.6 else 'Correlation is below 0.6; see the dataset caveat above before drawing conclusions.')}
- The offline probe is deterministic, so variance is {variance}. With the real
  model, this number measures sampling instability.
- Grounding is enforced by `quote_is_grounded`: a fabricated quote caps that
  criterion at 5 and adds `ungrounded_evidence` to the flags.
"""

    out = Path(args.out)
    out.write_text(report, encoding="utf-8")
    print(report)
    print(f"Wrote {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
