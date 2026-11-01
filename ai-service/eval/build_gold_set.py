"""Builds the synthetic 200-answer calibration set.

This is deliberately reproducible and dependency-free. It is a *synthetic*
gold set: human scores are authored per quality tier, not collected from real
raters. Replace it with human-labelled answers before making external claims
about model accuracy. The harness itself is production-shaped.
"""

import json
from pathlib import Path

TOPICS = [
    {
        "topic": "indexes",
        "question": "How would you speed up a slow query on a large table?",
        "expected": "Add an index on the filtered/sorted columns, check the query plan, avoid functions on indexed columns, and measure before and after.",
        "keywords": ["index", "query plan", "column", "measure", "composite"],
    },
    {
        "topic": "cap",
        "question": "Explain the CAP theorem and when you would choose AP over CP.",
        "expected": "Consistency, availability and partition tolerance cannot all hold during a partition; choose AP for availability during partitions, CP when correctness is non-negotiable.",
        "keywords": ["consistency", "availability", "partition", "trade-off", "CP", "AP"],
    },
    {
        "topic": "rest_graphql",
        "question": "When would you choose GraphQL over REST?",
        "expected": "GraphQL helps when clients need flexible field selection and many round trips matter; REST is simpler for stable resources and HTTP caching.",
        "keywords": ["GraphQL", "REST", "round trip", "caching", "schema", "over-fetching"],
    },
    {
        "topic": "gil",
        "question": "What is Python's GIL and what does it mean for concurrency?",
        "expected": "The GIL allows one thread to execute Python bytecode at a time; use processes or native I/O for CPU-bound parallelism, threads still help I/O.",
        "keywords": ["GIL", "bytecode", "thread", "process", "CPU-bound", "I/O"],
    },
    {
        "topic": "gc",
        "question": "How does garbage collection work in the JVM?",
        "expected": "Tracing collectors find reachable objects from GC roots, generational heaps separate young and old objects, and pauses depend on the collector and heap size.",
        "keywords": ["GC root", "generational", "young", "old", "pause", "collector"],
    },
    {
        "topic": "joins",
        "question": "Explain inner, left and full outer joins with an example.",
        "expected": "Inner returns matching rows only; left keeps all left rows with nulls on the right; full outer keeps unmatched rows from both sides.",
        "keywords": ["inner", "left", "full", "null", "matching", "unmatched"],
    },
    {
        "topic": "cache",
        "question": "How would you design a cache for a read-heavy API?",
        "expected": "Choose a cache-aside or write-through strategy, set TTLs, handle invalidation and stampedes, and measure hit rate and latency.",
        "keywords": ["cache-aside", "TTL", "invalidation", "hit rate", "stampede", "latency"],
    },
    {
        "topic": "race",
        "question": "What is a race condition and how do you prevent one?",
        "expected": "Two operations read and write shared state without ordering, producing an incorrect result; prevent it with locks, atomics or transactional constraints.",
        "keywords": ["shared state", "ordering", "lock", "atomic", "transaction", "idempotent"],
    },
    {
        "topic": "pagination",
        "question": "How do you paginate a large API result safely?",
        "expected": "Prefer cursor/keyset pagination over OFFSET for large data, keep a stable sort key, and cap page size.",
        "keywords": ["cursor", "keyset", "OFFSET", "sort key", "page size", "stable"],
    },
    {
        "topic": "pipeline",
        "question": "How do you make a data pipeline incremental instead of a nightly full reload?",
        "expected": "Track a watermark or change data capture, deduplicate by key, make loads idempotent, and backfill safely.",
        "keywords": ["watermark", "CDC", "deduplicate", "idempotent", "backfill", "incremental"],
    },
]

TIERS = [
    ("poor", 2.5, "It is faster and better. You just improve it."),
    ("weak", 4.0, "You should add something to make it work. It depends on the situation, but generally you fix the problem."),
    ("adequate", 6.0, "The main idea is to {keyword1} and then look at {keyword2}. That usually improves the result, though details depend on the system."),
    ("good", 7.8, "I would start by checking {keyword1}, then use {keyword2} and {keyword3}. For example, I measured the slow path first and compared before and after. The trade-off is extra write cost, so I would watch that."),
    ("excellent", 9.0, "In my last project I handled this by applying {keyword1} with {keyword2} and verifying {keyword3} against a production-like dataset. We measured 40% lower p95 latency, kept a rollback, and documented the trade-off: writes became slower. When {keyword4} mattered more, I chose the alternative and tested it with a benchmark."),
]

VARIANTS = [
    "",
    " I would confirm the assumption with a small reproduction first.",
    " The team reviewed the plan together before shipping it.",
    " I would add a metric and an alert so the change is observable.",
]


def human_scores(tier: str, base: float, variant: int) -> dict:
    jitter = [0.0, 0.1, -0.1, 0.2][variant % 4]
    offsets = {
        "relevance": 0.0,
        "technical_depth": -0.9 if tier in {"poor", "weak"} else (0.5 if tier == "excellent" else 0.0),
        "correctness": -0.5 if tier == "poor" else (0.2 if tier in {"adequate", "good", "excellent"} else 0.0),
        "clarity": -0.5 if tier == "poor" else (0.3 if tier in {"good", "excellent"} else 0.0),
        "evidence": {"poor": -1.5, "weak": -1.0, "adequate": 0.0, "good": 0.5, "excellent": 0.8}[tier],
    }
    return {
        criterion: round(max(0.0, min(10.0, base + offset + jitter)), 1)
        for criterion, offset in offsets.items()
    }


def build_answer(topic: dict, tier: str, base: float, variant: int) -> str:
    template = next(t[2] for t in TIERS if t[0] == tier)
    keywords = topic["keywords"]
    answer = template.format(
        keyword1=keywords[0], keyword2=keywords[1],
        keyword3=keywords[2], keyword4=keywords[3],
    )
    return answer + VARIANTS[variant]


def main() -> None:
    rows = []
    index = 1
    for topic in TOPICS:
        for tier, base, _ in TIERS:
            for variant in range(4):
                rows.append({
                    "id": index,
                    "topic": topic["topic"],
                    "tier": tier,
                    "variant": variant,
                    "question": topic["question"],
                    "answer": build_answer(topic, tier, base, variant),
                    "expected_answer": topic["expected"],
                    "human": human_scores(tier, base, variant),
                })
                index += 1

    out = Path(__file__).with_name("gold_set.jsonl")
    with out.open("w", encoding="utf-8") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"Wrote {len(rows)} examples to {out}")


if __name__ == "__main__":
    main()
