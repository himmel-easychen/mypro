#!/usr/bin/env python3
"""Run retrieval-side metrics against the 50-case RAG evaluation set."""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
import urllib.parse
import urllib.request
import urllib.error
import time
from pathlib import Path
from typing import Any, Callable


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def _answerable(row: dict[str, Any]) -> bool:
    return row.get("type") != "unanswerable"


def evaluate_records(
    records: list[dict[str, Any]],
    retrieve: Callable[[dict[str, Any]], list[str]],
    ks: list[int],
    parent_map: dict[str, str] | None = None,
) -> dict[str, Any]:
    answerable = [row for row in records if _answerable(row)]
    multi_hop = [row for row in answerable if row.get("type") == "multi_hop"]
    unanswerable = [row for row in records if not _answerable(row)]
    retrieved: dict[str, list[str]] = {}
    failures: list[dict[str, Any]] = []
    for row in records:
        try:
            retrieved[row["id"]] = list(retrieve(row))
        except Exception as exc:  # keep the report complete for one bad case
            retrieved[row["id"]] = []
            failures.append({"id": row.get("id"), "reason": str(exc)})

    retrieval_metrics: dict[str, float] = {}
    parent_metrics: dict[str, float] = {}
    for k in ks:
        hits = []
        recalls = []
        reciprocal_ranks = []
        for row in answerable:
            relevant = set(row.get("relevant_chunk_ids", []))
            top = retrieved[row["id"]][:k]
            overlap = relevant.intersection(top)
            hits.append(1.0 if overlap else 0.0)
            recalls.append(len(overlap) / len(relevant) if relevant else 0.0)
            reciprocal_ranks.append(next((1.0 / (index + 1) for index, chunk in enumerate(top) if chunk in relevant), 0.0))
        retrieval_metrics[f"hit@{k}"] = sum(hits) / len(hits) if hits else 0.0
        retrieval_metrics[f"recall@{k}"] = sum(recalls) / len(recalls) if recalls else 0.0
        if k == 5:
            retrieval_metrics["mrr@5"] = sum(reciprocal_ranks) / len(reciprocal_ranks) if reciprocal_ranks else 0.0
        if k == 10:
            ndcgs = []
            for row in answerable:
                relevant = set(row.get("relevant_chunk_ids", []))
                top = retrieved[row["id"]][:k]
                dcg = sum(1.0 / math.log2(index + 2) for index, chunk in enumerate(top) if chunk in relevant)
                # Binary relevance: the ideal ranking puts all relevant chunks first.
                ideal_count = min(len(relevant), k)
                idcg = sum(1.0 / math.log2(index + 2) for index in range(ideal_count))
                ndcgs.append(dcg / idcg if idcg else 0.0)
            retrieval_metrics["ndcg@10"] = sum(ndcgs) / len(ndcgs) if ndcgs else 0.0
        if parent_map:
            parent_hits = []
            parent_recalls = []
            for row in answerable:
                relevant = {parent_map.get(chunk, chunk) for chunk in row.get("relevant_chunk_ids", [])}
                top = {parent_map.get(chunk, chunk) for chunk in retrieved[row["id"]][:k]}
                overlap = relevant.intersection(top)
                parent_hits.append(1.0 if overlap else 0.0)
                parent_recalls.append(len(overlap) / len(relevant) if relevant else 0.0)
            parent_metrics[f"hit@{k}"] = sum(parent_hits) / len(parent_hits) if parent_hits else 0.0
            parent_metrics[f"recall@{k}"] = sum(parent_recalls) / len(parent_recalls) if parent_recalls else 0.0

    # MMR was used colloquially in the request; the established retrieval
    # metric is MRR (Mean Reciprocal Rank). Keep an alias in the report so the
    # terminology is unambiguous to downstream consumers.
    if "mrr@5" in retrieval_metrics:
        retrieval_metrics["mmr@5"] = retrieval_metrics["mrr@5"]

    complete: dict[str, float] = {}
    for k in ks:
        values = []
        for row in multi_hop:
            top = set(retrieved[row["id"]][:k])
            values.append(1.0 if set(row.get("relevant_chunk_ids", [])).issubset(top) else 0.0)
        complete[f"complete@{k}"] = sum(values) / len(values) if values else 0.0

    parent_complete: dict[str, float] = {}
    if parent_map:
        for k in ks:
            values = []
            for row in multi_hop:
                relevant = {parent_map.get(chunk, chunk) for chunk in row.get("relevant_chunk_ids", [])}
                top = {parent_map.get(chunk, chunk) for chunk in retrieved[row["id"]][:k]}
                values.append(1.0 if relevant.issubset(top) else 0.0)
            parent_complete[f"complete@{k}"] = sum(values) / len(values) if values else 0.0

    report = {
        "counts": {"total": len(records), "answerable": len(answerable), "unanswerable": len(unanswerable), "multi_hop": len(multi_hop)},
        "retrieval": retrieval_metrics,
        "parent_retrieval": parent_metrics,
        "multi_hop": complete,
        "multi_hop_parent": parent_complete,
        "compare_mutation": {"numeric_accuracy": None, "source_location_accuracy": None},
        "refusal": {"eligible": len(unanswerable), "accuracy": None, "note": "当前搜索 API 只返回证据，不生成答案；拒答准确率需接入 answer chain。"},
        "context_confusion": {"target_preservation": None, "distractor_adoption_rate": None},
        "failures": failures,
    }
    report["gates"] = gate_results(report)
    return report


def gate_results(report: dict[str, Any]) -> dict[str, Any]:
    checks = {
        "Recall@5": (report["retrieval"].get("recall@5"), 0.90, "gte"),
        "MRR@5": (report["retrieval"].get("mrr@5"), 0.80, "gte"),
        "NDCG@10": (report["retrieval"].get("ndcg@10"), 0.80, "gte"),
    }
    results = {}
    for name, (value, threshold, direction) in checks.items():
        passed = value is not None and (value >= threshold if direction == "gte" else value <= threshold)
        results[name] = {"value": value, "threshold": threshold, "passed": passed}
    return {"passed": all(item["passed"] for item in results.values()), "checks": results}


def http_retrieve(base_url: str, row: dict[str, Any], top_k: int, mode: str = "auto",
                  disable_query_transform: bool = False,
                  full_corpus: bool = False) -> list[str]:
    params = {"query": row["query"], "topK": top_k, "mode": mode}
    if disable_query_transform:
        params["disableQueryTransform"] = "true"
    # Evaluation rows carry the authoritative source document.  Filtering by
    # it prevents unrelated files in the shared corpus from contaminating a
    # per-document retrieval score.
    if not full_corpus and row.get("source_document_id"):
        params["documentId"] = row["source_document_id"]
    query = urllib.parse.urlencode(params)
    with urllib.request.urlopen(f"{base_url.rstrip('/')}/api/rag/search?{query}", timeout=120) as response:
        payload = json.load(response)
    return [item["chunk_id"] for item in payload if isinstance(item, dict) and item.get("chunk_id")]


def http_retrieve_diagnostics(base_url: str, row: dict[str, Any], top_k: int,
                              disable_query_transform: bool = False,
                              full_corpus: bool = False) -> dict[str, list[str]]:
    params: dict[str, Any] = {"query": row["query"], "topK": top_k, "mode": "auto", "diagnostics": "true"}
    if disable_query_transform:
        params["disableQueryTransform"] = "true"
    if not full_corpus and row.get("source_document_id"):
        params["documentId"] = row["source_document_id"]
    query = urllib.parse.urlencode(params)
    with urllib.request.urlopen(f"{base_url.rstrip('/')}/api/rag/search?{query}", timeout=120) as response:
        payload = json.load(response)
    diagnostics = payload.get("diagnostics", {})
    stages = ("vector", "bm25", "structured", "fused", "reranked", "final_context")
    return {
        stage: [item["chunkId"] for item in diagnostics.get(stage, [])
                if isinstance(item, dict) and item.get("chunkId")]
        for stage in stages
    }


def classify_multihop_failures(records: list[dict[str, Any]],
                               layered: dict[str, dict[str, list[str]]],
                               parent_map: dict[str, str] | None = None) -> dict[str, Any]:
    categories = ("provider_miss", "partial_provider_recall", "fusion_drop",
                  "rerank_drop", "context_drop", "complete")
    stage_names = ("vector", "bm25", "structured", "fused", "reranked", "final_context")
    cases = []
    funnel = {"total": 0, "provider_any": 0, "provider_complete": 0,
              "fused_complete": 0, "reranked_complete": 0, "final_context_complete": 0}
    counts = {category: 0 for category in categories}
    for row in records:
        if row.get("type") != "multi_hop":
            continue
        funnel["total"] += 1
        gold = list(dict.fromkeys(row.get("relevant_chunk_ids", [])))
        stages = layered.get(row["id"], {})
        child_parent = parent_map or {}
        gold_set = {child_parent.get(chunk_id, chunk_id) for chunk_id in gold}
        parent_stages = {
            stage: {child_parent.get(chunk_id, chunk_id) for chunk_id in stages.get(stage, [])}
            for stage in stage_names
        }
        provider_union = set().union(*(parent_stages[stage] for stage in stage_names[:3]))
        if gold_set & provider_union:
            funnel["provider_any"] += 1
        provider_complete = gold_set.issubset(provider_union)
        fused_complete = gold_set.issubset(parent_stages["fused"])
        reranked_complete = gold_set.issubset(parent_stages["reranked"])
        context_complete = gold_set.issubset(parent_stages["final_context"])
        funnel["provider_complete"] += int(provider_complete)
        funnel["fused_complete"] += int(fused_complete)
        funnel["reranked_complete"] += int(reranked_complete)
        funnel["final_context_complete"] += int(context_complete)
        if not gold_set & provider_union:
            category = "provider_miss"
        elif not provider_complete:
            category = "partial_provider_recall"
        elif not fused_complete:
            category = "fusion_drop"
        elif not reranked_complete:
            category = "rerank_drop"
        elif not context_complete:
            category = "context_drop"
        else:
            category = "complete"
        counts[category] += 1
        gold_chunks = []
        for chunk_id in gold:
            ranks = {}
            for stage in stage_names:
                values = stages.get(stage, [])
                ranks[stage] = values.index(chunk_id) + 1 if chunk_id in values else None
            gold_chunks.append({"chunk_id": chunk_id,
                                "parent_chunk_id": child_parent.get(chunk_id, chunk_id),
                                "ranks": ranks})
        cases.append({"id": row["id"], "query": row.get("query"), "category": category,
                      "coverage_basis": "parent_chunk", "gold_chunks": gold_chunks})
    total = funnel["total"]
    return {"category_counts": counts,
            "category_rates": {key: value / total if total else 0.0 for key, value in counts.items()},
            "funnel": funnel, "cases": cases}


def layer_metrics(records: list[dict[str, Any]], layered: dict[str, dict[str, list[str]]], layer: str) -> dict[str, float]:
    answerable = [row for row in records if _answerable(row)]
    values: dict[str, float] = {}
    for metric, k in (("recall@5", 5), ("mrr@5", 5), ("ndcg@10", 10)):
        scores = []
        for row in answerable:
            relevant = set(row.get("relevant_chunk_ids", []))
            top = layered[row["id"]].get(layer, [])[:k]
            if metric == "recall@5":
                scores.append(len(relevant.intersection(top)) / len(relevant) if relevant else 0.0)
            elif metric == "mrr@5":
                scores.append(next((1.0 / (i + 1) for i, chunk in enumerate(top) if chunk in relevant), 0.0))
            else:
                dcg = sum(1.0 / math.log2(i + 2) for i, chunk in enumerate(top) if chunk in relevant)
                idcg = sum(1.0 / math.log2(i + 2) for i in range(min(len(relevant), k)))
                scores.append(dcg / idcg if idcg else 0.0)
        values[metric] = sum(scores) / len(scores) if scores else 0.0
    return values


def layer_metrics_by_type(records: list[dict[str, Any]], layered: dict[str, dict[str, list[str]]], layer: str) -> dict[str, dict[str, float]]:
    grouped: dict[str, list[dict[str, Any]]] = {}
    for row in records:
        grouped.setdefault(str(row.get("type", "unknown")), []).append(row)
    return {kind: layer_metrics(rows, layered, layer) for kind, rows in grouped.items()}


def http_answer(base_url: str, row: dict[str, Any], disable_query_transform: bool = False,
                full_corpus: bool = False) -> dict[str, Any]:
    # Only user input and document scope cross the production boundary. Never send gold/type/expected.
    body = {"query": row["query"], "documentId": row.get("source_document_id"),
            "disableQueryTransform": disable_query_transform}
    if full_corpus:
        body.pop("documentId")
    request = urllib.request.Request(base_url.rstrip("/") + "/api/rag/answer",
                                     json.dumps(body).encode("utf-8"), {"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=180) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        payload = json.loads(error.read().decode("utf-8"))
        if isinstance(payload, dict) and "status" in payload and "answer" in payload:
            return payload
        raise


def evaluate_generation(records: list[dict[str, Any]], answer: Callable, ks: list[int],
                        review: dict[str, dict[str, Any]] | None = None) -> dict[str, Any]:
    samples = []
    responses = {}
    for row in records:
        started = time.monotonic()
        try:
            response = answer(row)
        except Exception as error:
            response = {"status": "ERROR", "error": type(error).__name__, "answer": "",
                        "prompt": "", "evidence": [], "retrievedChunkIds": [],
                        "latencyMs": round((time.monotonic() - started) * 1000)}
        responses[row["id"]] = response
        ok = response.get("status") == "OK"
        text = response.get("answer") or ""
        refusal = ok and response.get("refusal") is True
        evidence = response.get("evidence") or []
        ids = {e.get("id") for e in evidence}
        cited = re.findall(r"\[(E\d+)\]", text)
        mapped = bool(cited) and set(cited).issubset(ids)
        expected = str(row.get("expected", {}).get("answer", ""))
        # Screening signals only: semantic correctness, units and citation entailment require review.
        numbers = re.findall(r"(?<!\d)\d+(?:\.\d+)?", expected)
        answer_numbers = set(re.findall(r"(?<!\d)\d+(?:\.\d+)?", re.sub(r"\[E\d+\]", "", text)))
        buckets = []
        if not ok:
            buckets.append("citation_error" if response.get("error") == "INVALID_CITATIONS" else "infrastructure_error")
        elif not _answerable(row):
            if not refusal:
                buckets.append("refusal_error")
        else:
            gold = set(row.get("relevant_chunk_ids", []))
            retrieved = set(response.get("retrievedChunkIds", []))
            if not gold.issubset(retrieved):
                buckets.append("retrieval_miss")
            if refusal:
                buckets.append("refusal_error")
            elif not mapped:
                buckets.append("citation_error")
            if not text.strip():
                buckets.append("llm_answer_error")
        samples.append({"id": row["id"], "type": row.get("type"), "query": row["query"],
                        "expected": row.get("expected"), "response": response,
                        "checks": {"answer_present": ok and bool(text.strip()), "refusal": refusal,
                                   "citation_ids_mapped": mapped if ok and not refusal else None,
                                   "expected_numbers_present": all(n in answer_numbers for n in numbers)
                                   if ok and _answerable(row) and numbers else None},
                        "review": {"status": "pending", "required_facts_covered": None,
                                   "numeric_unit_correct": None, "citation_supported": None,
                                   "notes": ""}, "failure_buckets": buckets})
    report = evaluate_records(records, lambda row: responses[row["id"]].get("retrievedChunkIds", []), ks)
    usable = [s for s in samples if s["response"].get("status") == "OK"]
    answerable = [s for s in usable if s["type"] != "unanswerable"]
    unanswerable = [s for s in usable if s["type"] == "unanswerable"]
    def ratio(items, predicate):
        return sum(bool(predicate(s)) for s in items) / len(items) if items else None
    latencies = sorted(s["response"].get("latencyMs", 0) for s in samples)
    report["generation"] = {"attempted": len(samples), "measured": len(usable),
                            "answer_present_rate": ratio(answerable, lambda s: s["checks"]["answer_present"]),
                            "false_refusal_count": sum(s["checks"]["refusal"] for s in answerable),
                            "required_facts_accuracy": None, "numeric_unit_accuracy": None,
                            "review_status": "pending", "scope": "document_filtered"}
    report["refusal"] = {"eligible": sum(s["type"] == "unanswerable" for s in samples),
                         "measured": len(unanswerable),
                         "accuracy": ratio(unanswerable, lambda s: s["checks"]["refusal"]),
                         "false_answer_rate": ratio(unanswerable, lambda s: not s["checks"]["refusal"])}
    report["citations"] = {"id_mapping_rate": ratio([s for s in answerable if not s["checks"]["refusal"]],
                                                     lambda s: s["checks"]["citation_ids_mapped"]),
                           "support_accuracy": None, "note": "ID 存在不等于事实被引用支持；须逐条复核"}
    report["latency"] = {"p50_ms": latencies[len(latencies)//2] if latencies else None,
                         "p95_ms": latencies[max(0, math.ceil(len(latencies)*.95)-1)] if latencies else None}
    report["context_confusion"] = {"status": "not_measured", "note": "未向生成接口注入干扰证据，不声明抗干扰通过"}
    report["samples"] = samples
    report["failure_table"] = [{"id": s["id"], "bucket": bucket} for s in samples for bucket in s["failure_buckets"]]
    for sample in samples:
        if review and sample["id"] in review:
            sample["review"] = review[sample["id"]]
            waived = set(sample["review"].get("waived_failure_buckets", []))
            if waived:
                sample["failure_buckets"] = [bucket for bucket in sample["failure_buckets"] if bucket not in waived]
            # A negative review must block acceptance even if the reviewer omitted a bucket.
            if sample["review"].get("required_facts_covered") is False or sample["review"].get("numeric_unit_correct") is False:
                if "llm_answer_error" not in sample["failure_buckets"]:
                    sample["failure_buckets"].append("llm_answer_error")
            if sample["review"].get("citation_supported") is False:
                if "citation_error" not in sample["failure_buckets"]:
                    sample["failure_buckets"].append("citation_error")
            for bucket in sample["review"].get("failure_buckets", []):
                if bucket not in sample["failure_buckets"]:
                    sample["failure_buckets"].append(bucket)
    reviewed = all(s["review"].get("status") == "reviewed" for s in samples) and bool(samples)
    if reviewed:
        report["generation"]["review_status"] = "reviewed"
        report["generation"]["required_facts_accuracy"] = ratio(answerable, lambda s: s["review"].get("required_facts_covered") is True)
        numeric = [s for s in answerable if s["review"].get("numeric_unit_correct") is not None]
        report["generation"]["numeric_unit_accuracy"] = ratio(numeric, lambda s: s["review"]["numeric_unit_correct"])
        cited_samples = [s for s in answerable if not s["checks"]["refusal"]]
        report["citations"]["support_accuracy"] = ratio(cited_samples, lambda s: s["review"].get("citation_supported") is True)
    report["failure_table"] = [{"id": s["id"], "bucket": bucket} for s in samples for bucket in s["failure_buckets"]]
    report["closed_loop_passed"] = reviewed and len(usable) == len(samples) and report["gates"]["passed"] and not report["failure_table"]
    return report


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("eval_path", type=Path)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--generation", action="store_true", help="调用真实 /api/rag/answer 并保留 prompt/answer/trace")
    parser.add_argument("--replay", type=Path, help="复用已保存生成响应，复核时不再次调用模型")
    parser.add_argument("--review", type=Path, help="逐条复核 JSON，以样本 ID 为键")
    parser.add_argument("--full-corpus", action="store_true",
                        help="检索和生成评测均不发送 documentId，保留原 gold 供对照")
    parser.add_argument("--case-id", action="append", dest="case_ids",
                        help="仅评测指定样本；可重复传入多个 ID")
    parser.add_argument("--k", nargs="+", type=int, default=[1, 3, 5, 10])
    parser.add_argument("--diagnostics", action="store_true", help="按 provider 分层记录 Vector/BM25/Structured/Auto 结果")
    parser.add_argument("--disable-query-transform", action="store_true",
                        help="评测消融：禁用 query decomposition，保持其他检索配置不变")
    parser.add_argument("--failure-analysis", action="store_true",
                        help="使用线上逐层 trace 定位 multi-hop gold chunk 丢失阶段")
    parser.add_argument("--parent-map", type=Path,
                        help="JSON 映射：child chunk_id 到 parent_chunk_id，用于 parent 级覆盖率")
    args = parser.parse_args(argv)
    records = read_jsonl(args.eval_path)
    parent_map = {}
    if args.parent_map:
        parent_map = json.loads(args.parent_map.read_text(encoding="utf-8"))
    if args.case_ids:
        requested = set(args.case_ids)
        records = [record for record in records if record["id"] in requested]
        missing = requested - {record["id"] for record in records}
        if missing:
            parser.error("Unknown case ID: " + ", ".join(sorted(missing)))
    if args.generation:
        reviews = json.loads(args.review.read_text(encoding="utf-8")) if args.review else None
        if args.replay:
            saved = json.loads(args.replay.read_text(encoding="utf-8"))
            responses = {sample["id"]: sample["response"] for sample in saved["samples"]}
            answer_call = lambda row: responses[row["id"]]
        else:
            def answer_call(row):
                response = http_answer(args.base_url, row, args.disable_query_transform, args.full_corpus)
                print(row["id"], response.get("status"), response.get("latencyMs"), file=sys.stderr, flush=True)
                return response
        report = evaluate_generation(records, answer_call, args.k, reviews)
        report["generation"]["scope"] = "full_corpus" if args.full_corpus else "document_filtered"
        if args.full_corpus:
            report["scope_note"] = "原标签针对指定文档；全库出现其他有效证据或指代歧义须人工复核，gold指标只作固定集合对照。"
        output = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
        if args.output:
            args.output.write_text(output, encoding="utf-8")
        print(json.dumps({key: value for key, value in report.items() if key != "samples"}, ensure_ascii=False, indent=2))
        return 0 if report["closed_loop_passed"] else 2
    report = evaluate_records(records, lambda row: http_retrieve(
        args.base_url, row, max(args.k), disable_query_transform=args.disable_query_transform,
        full_corpus=args.full_corpus), args.k, parent_map)
    report["scope"] = "full_corpus" if args.full_corpus else "document_filtered"
    if args.full_corpus:
        report["scope_note"] = "原标签针对指定文档；全库 gold 指标是固定集合对照，其他同名或同义有效证据需人工复核。"
    if args.diagnostics:
        layers = ("vector", "bm25", "structured", "auto")
        layered: dict[str, dict[str, list[str]]] = {}
        for row in records:
            layered[row["id"]] = {
                layer: http_retrieve(args.base_url, row, max(args.k), layer,
                                     disable_query_transform=args.disable_query_transform,
                                     full_corpus=args.full_corpus)
                for layer in layers
            }
        report["layered_diagnostics"] = {
            "layers": {layer: layer_metrics(records, layered, layer) for layer in layers},
            "by_type": {layer: layer_metrics_by_type(records, layered, layer) for layer in layers},
            "samples": layered,
            "interpretation": {
                "retrieval": "Vector/Elasticsearch BM25/Structured 各自命中率",
                "fusion_rerank": "Auto 相对单路的变化，当前为 RRF，不是 LLM reranker",
                "prompt_llm": "本 Runner 只测搜索 API，没有调用 answer prompt 或 LLM，因此不在本次低分归因范围内",
            },
        }
    if args.failure_analysis:
        staged: dict[str, dict[str, list[str]]] = {}
        diagnostic_failures = []
        for row in records:
            if row.get("type") != "multi_hop":
                continue
            try:
                staged[row["id"]] = http_retrieve_diagnostics(
                    args.base_url, row, max(20, max(args.k)), args.disable_query_transform,
                    args.full_corpus)
            except Exception as exc:
                staged[row["id"]] = {}
                diagnostic_failures.append({"id": row.get("id"), "reason": str(exc)})
        report["multi_hop_failure_analysis"] = classify_multihop_failures(records, staged, parent_map)
        report["multi_hop_failure_analysis"]["failures"] = diagnostic_failures
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    if args.output:
        args.output.write_text(text, encoding="utf-8")
    print(text, end="")
    return 0 if not report["failures"] and report["gates"]["passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
