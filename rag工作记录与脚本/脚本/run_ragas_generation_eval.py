"""Run generation evaluation with DeepSeek Flash and Ragas.

The production answer endpoint supplies the generated answer and the exact
evidence passed to the answer model. Ragas is then applied to answerable cases
using DeepSeek Flash as the judge. Unanswerable cases are reported separately
because the standard Ragas reference-answer metrics do not model refusal.
"""

from __future__ import annotations

import argparse
import json
import math
import hashlib
import importlib.metadata
import os
import time
import urllib.error
import urllib.request
import urllib.parse
from pathlib import Path
from typing import Any
from langchain_core.embeddings import Embeddings


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def answer(base_url: str, row: dict[str, Any], full_corpus: bool, timeout: int) -> dict[str, Any]:
    body: dict[str, Any] = {
        "query": row["query"],
        "documentId": row.get("source_document_id"),
        "disableQueryTransform": False,
    }
    if full_corpus:
        body.pop("documentId", None)
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/rag/answer",
        json.dumps(body, ensure_ascii=False).encode("utf-8"),
        {"Content-Type": "application/json"},
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        payload = json.loads(error.read().decode("utf-8"))
        if isinstance(payload, dict) and "answer" in payload:
            return payload
        raise


def context_text(evidence: dict[str, Any]) -> str:
    content = str(evidence.get("content") or "").strip()
    parent = str(evidence.get("parentContent") or "").strip()
    if parent and parent != content:
        return content + "\n" + parent
    return content


class DashScopeTextEmbeddings(Embeddings):
    """LangChain embedding adapter using DashScope's native text-embedding-v4 API."""

    def __init__(self, api_key: str, base_url: str, model: str, dimension: int, timeout: int):
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model = model
        self.dimension = dimension
        self.timeout = timeout

    def embed_documents(self, texts: list[str]) -> list[list[float]]:
        output: list[list[float] | None] = [None] * len(texts)
        for offset in range(0, len(texts), 10):
            batch = texts[offset:offset + 10]
            body = {"model": self.model, "input": {"texts": batch},
                    "parameters": {"dimension": self.dimension, "output_type": "dense"}}
            request = urllib.request.Request(
                self.base_url + "/services/embeddings/text-embedding/text-embedding",
                json.dumps(body, ensure_ascii=False).encode("utf-8"),
                {"Content-Type": "application/json", "Authorization": f"Bearer {self.api_key}"},
            )
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.load(response)
            items = payload.get("output", {}).get("embeddings", [])
            if len(items) != len(batch):
                raise RuntimeError("DashScope embedding response count mismatch")
            for item in items:
                index = int(item["text_index"])
                output[offset + index] = item["embedding"]
        if any(item is None for item in output):
            raise RuntimeError("DashScope embedding response omitted one or more texts")
        return [item for item in output if item is not None]

    def embed_query(self, text: str) -> list[float]:
        return self.embed_documents([text])[0]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("eval_path", type=Path)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--output", type=Path, required=True)
    scope = parser.add_mutually_exclusive_group()
    # Full-corpus is the comparable evaluation scope. Document filtering is
    # an explicit diagnostic mode so it cannot silently inflate recall.
    scope.add_argument("--full-corpus", dest="full_corpus", action="store_true")
    scope.add_argument("--document-filtered", dest="full_corpus", action="store_false")
    parser.set_defaults(full_corpus=True)
    parser.add_argument("--timeout", type=int, default=240)
    parser.add_argument("--judge-model", default="deepseek-flash")
    parser.add_argument("--judge-base-url", default="https://api.deepseek.com/v1")
    parser.add_argument("--embedding-model", default="text-embedding-v4")
    parser.add_argument("--embedding-base-url", default="https://dashscope.aliyuncs.com/api/v1")
    parser.add_argument("--responses", type=Path,
                        help="JSONL checkpoint; existing sample responses are reused and new ones are appended")
    args = parser.parse_args()

    from datasets import Dataset
    from langchain_openai import ChatOpenAI
    from ragas import evaluate
    from ragas.metrics import answer_relevancy, context_precision, context_recall, faithfulness

    deepseek_key = os.environ["RAGAS_JUDGE_API_KEY"]
    embedding_key = os.environ.get("RAGAS_EMBEDDING_API_KEY")
    if not embedding_key:
        raise SystemExit("RAGAS_EMBEDDING_API_KEY is required for answer_relevancy")

    records = read_jsonl(args.eval_path)
    samples: list[dict[str, Any]] = []
    response_path = args.responses or args.output.with_suffix(".responses.jsonl")
    response_path.parent.mkdir(parents=True, exist_ok=True)
    cached: dict[str, dict[str, Any]] = {}
    if response_path.exists():
        for line in response_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                item = json.loads(line)
                cached[item["id"]] = item["response"]
    for index, row in enumerate(records, 1):
        response = cached.get(row["id"])
        if response is None:
            started = time.monotonic()
            try:
                response = answer(args.base_url, row, args.full_corpus, args.timeout)
            except Exception as exc:
                response = {"status": "ERROR", "answer": "", "evidence": [],
                            "retrievedChunkIds": [], "error": f"{type(exc).__name__}: {exc}"}
            response.setdefault("latencyMs", round((time.monotonic() - started) * 1000))
            with response_path.open("a", encoding="utf-8") as checkpoint:
                checkpoint.write(json.dumps({"id": row["id"], "response": response},
                                            ensure_ascii=False) + "\n")
        evidence = response.get("evidence") or []
        samples.append({"id": row["id"], "type": row.get("type"), "query": row["query"],
                        "expected": row.get("expected", {}), "response": response,
                        "refusal": response.get("refusal") is True})
        print(f"[{index}/{len(records)}] {row['id']} {response.get('status')}", flush=True)

    answerable_rows = []
    answerable_samples = []
    for sample in samples:
        if sample["type"] == "unanswerable":
            continue
        response = sample["response"]
        if response.get("status") != "OK" or not (response.get("answer") or "").strip():
            continue
        contexts = [context_text(item) for item in response.get("evidence") or []]
        contexts = [item for item in contexts if item]
        answerable_rows.append({
            "user_input": sample["query"],
            "response": response.get("answer") or "",
            "retrieved_contexts": contexts,
            "reference": str(sample["expected"].get("answer") or ""),
        })
        answerable_samples.append(sample)

    dataset = Dataset.from_list(answerable_rows)
    judge = ChatOpenAI(model=args.judge_model, api_key=deepseek_key,
                       base_url=args.judge_base_url, temperature=0,
                       max_tokens=2048, timeout=args.timeout, max_retries=2)
    embeddings = DashScopeTextEmbeddings(embedding_key, args.embedding_base_url,
                                         args.embedding_model, 1024, args.timeout)
    result = evaluate(dataset, metrics=[faithfulness, answer_relevancy,
                                        context_precision, context_recall],
                      llm=judge, embeddings=embeddings, raise_exceptions=False,
                      show_progress=True)
    scores = result.to_pandas().to_dict(orient="records")
    aggregate = result.to_pandas().mean(numeric_only=True).to_dict()
    targets = {"faithfulness": 0.90, "answer_relevancy": 0.85,
               "context_precision": 0.85, "context_recall": 1.00}
    target_comparison = {}
    for metric, target in targets.items():
        value = aggregate.get(metric)
        if value is None or not math.isfinite(float(value)):
            target_comparison[metric] = {"value": None, "target": target,
                                         "gap": None, "passed": False}
        else:
            value = float(value)
            target_comparison[metric] = {"value": value, "target": target,
                                         "gap": round(value - target, 6),
                                         "passed": value >= target}
    for sample, score in zip(answerable_samples, scores):
        sample["ragas"] = score

    answerable = [sample for sample in samples if sample["type"] != "unanswerable"]
    unanswerable = [sample for sample in samples if sample["type"] == "unanswerable"]
    successful = [sample for sample in samples if sample["response"].get("status") == "OK"]
    output = {
        "dataset": str(args.eval_path),
        "dataset_sha256": hashlib.sha256(args.eval_path.read_bytes()).hexdigest(),
        "scope": "full_corpus" if args.full_corpus else "document_filtered",
        "query_transform_enabled": True,
        "ragas_version": importlib.metadata.version("ragas"),
        "model": {"provider": "DeepSeek", "model": args.judge_model,
                  "base_url": args.judge_base_url},
        "embedding": {"provider": "DashScope OpenAI-compatible", "model": args.embedding_model},
        "counts": {"total": len(samples), "answerable": len(answerable),
                   "ragas_measured": len(answerable_samples),
                   "unanswerable": len(unanswerable), "successful": len(successful)},
        "ragas": {"metrics": ["faithfulness", "answer_relevancy", "context_precision", "context_recall"],
                  "aggregate": aggregate, "targets": target_comparison,
                  "valid_counts": {metric: sum(isinstance(s.get(metric), (int, float))
                        and math.isfinite(s[metric]) for s in scores) for metric in targets},
                  "complete": len(answerable_samples) == len(answerable) and all(
                        all(isinstance(s.get(metric), (int, float)) and math.isfinite(s[metric])
                            for s in scores) for metric in targets),
                  "all_targets_met": len(answerable_samples) == len(answerable) and all(
                        all(isinstance(s.get(metric), (int, float)) and math.isfinite(s[metric])
                            for s in scores) for metric in targets) and all(
                        item["passed"] for item in target_comparison.values())},
        "response_checkpoint": str(response_path),
        "refusal": {"eligible": len(unanswerable),
                    "measured": sum(1 for sample in unanswerable if sample["response"].get("status") == "OK"),
                    "correct": sum(1 for sample in unanswerable if sample["refusal"]),
                    "accuracy": (sum(1 for sample in unanswerable if sample["refusal"]) / len(unanswerable)
                                 if unanswerable else None)},
        "latency": {"p50_ms": sorted(sample["response"].get("latencyMs", 0) for sample in samples)[len(samples)//2]
                    if samples else None},
        "samples": samples,
    }
    args.output.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"counts": output["counts"], "ragas": output["ragas"],
                      "refusal": output["refusal"]}, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
