"""Export stage-level and prompt-level gold evidence presence.

This audit is intentionally mechanical: it checks exact chunk IDs and whether a
gold chunk's text appears in prompt evidence or parent evidence. It does not
judge whether an alternative passage also supports the same fact.
"""
import argparse
import json
from pathlib import Path
from run_rag_eval import read_jsonl
from run_rag_distractor_eval import load_chunks

def _ratio(values):
    return sum(values) / len(values) if values else None

def _recall(gold, found):
    return len(set(gold) & set(found)) / len(set(gold)) if gold else None

def _candidate_index(candidates):
    """Index stage candidates by exact chunk id and retain the reported rank."""
    return {
        candidate.get("chunkId"): {
            "rank": candidate.get("rank"),
            "parentChunkId": candidate.get("parentChunkId"),
            "finalScore": candidate.get("finalScore"),
        }
        for candidate in candidates
        if candidate.get("chunkId")
    }

def _stage_diagnostic(stages, chunk_id):
    """Locate the first stage at which a gold chunk disappears.

    Provider stages are treated as one union because fusion receives their
    combined candidate set. A chunk can therefore be present in any provider
    and still be eligible for fusion.
    """
    provider_names = ("vector", "bm25", "structured")
    indexes = {name: _candidate_index(stages.get(name, [])) for name in stages}
    provider_hits = {
        name: indexes.get(name, {}).get(chunk_id)
        for name in provider_names
        if chunk_id in indexes.get(name, {})
    }
    fused = indexes.get("fused", {}).get(chunk_id)
    reranked = indexes.get("reranked", {}).get(chunk_id)
    final_context = indexes.get("final_context", {}).get(chunk_id)
    if not provider_hits:
        first_loss = "provider"
    elif fused is None:
        first_loss = "fusion"
    elif reranked is None:
        first_loss = "rerank"
    elif final_context is None:
        first_loss = "context"
    else:
        first_loss = None
    return {
        "chunk_id": chunk_id,
        "first_loss_stage": first_loss,
        "provider_hits": provider_hits,
        "fused": fused,
        "reranked": reranked,
        "final_context": final_context,
    }

def build_audit(rows, report, chunks, search_k=20):
    audits = []
    answerable_audits = []
    for sample in report["samples"]:
        row = rows[sample["id"]]
        response = sample["response"]
        stages = response.get("retrieval", {}).get("stage_candidates", {})
        evidence = response.get("evidence", [])
        retrieved = response.get("retrievedChunkIds", [])
        prompt_gold = []
        audit = {"id": sample["id"], "status": response.get("status"),
                 "error": response.get("error"), "refusal": response.get("refusal"),
                 "prompt_chars": len(response.get("prompt", "")),
                 "attempts": len(response.get("retrieval", {}).get("generation_attempts", [])),
                 "search_recall_at_k": _recall(row.get("relevant_chunk_ids", []), retrieved[:search_k]),
                 "search_recall_all_returned": _recall(row.get("relevant_chunk_ids", []), retrieved),
                 "prompt_recall": None,
                 "gold": []}
        for chunk_id in row.get("relevant_chunk_ids", []):
            content = chunks[chunk_id]["content"]
            matching = [e["id"] for e in evidence if e.get("chunkId") == chunk_id
                        or content in e.get("content", "") or content in e.get("parentContent", "")]
            if matching:
                prompt_gold.append(chunk_id)
            diagnostic = _stage_diagnostic(stages, chunk_id)
            diagnostic["stages"] = [name for name, candidates in stages.items()
                                    if any(c.get("chunkId") == chunk_id for c in candidates)]
            diagnostic["prompt_evidence_ids"] = matching
            audit["gold"].append(diagnostic)
        audit["prompt_recall"] = _recall(row.get("relevant_chunk_ids", []), prompt_gold)
        audits.append(audit)
        if row.get("type") != "unanswerable":
            answerable_audits.append(audit)
    gold_diagnostics = [gold for audit in answerable_audits for gold in audit["gold"]]
    loss_counts = {stage: sum(g["first_loss_stage"] == stage for g in gold_diagnostics)
                   for stage in ("provider", "fusion", "rerank", "context")}
    summary = {
        "total": len(audits),
        "answerable": len(answerable_audits),
        "search_k": search_k,
        "search_recall_at_k_mean": _ratio([a["search_recall_at_k"] for a in answerable_audits
                                           if a["search_recall_at_k"] is not None]),
        "search_recall_all_returned_mean": _ratio([a["search_recall_all_returned"] for a in answerable_audits
                                                   if a["search_recall_all_returned"] is not None]),
        "prompt_evidence_recall_mean": _ratio([a["prompt_recall"] for a in answerable_audits
                                               if a["prompt_recall"] is not None]),
        "search_complete_at_k_rate": _ratio([a["search_recall_at_k"] == 1.0 for a in answerable_audits]),
        "search_complete_all_returned_rate": _ratio([a["search_recall_all_returned"] == 1.0 for a in answerable_audits]),
        "prompt_complete_rate": _ratio([a["prompt_recall"] == 1.0 for a in answerable_audits]),
        "prompt_loss_after_search_k_complete": [
            a["id"] for a in answerable_audits
            if a["search_recall_at_k"] == 1.0 and a["prompt_recall"] != 1.0
        ],
        "prompt_loss_after_search_all_returned_complete": [
            a["id"] for a in answerable_audits
            if a["search_recall_all_returned"] == 1.0 and a["prompt_recall"] != 1.0
        ],
        "search_loss_at_k": [a["id"] for a in answerable_audits if a["search_recall_at_k"] != 1.0],
        "search_loss_all_returned": [a["id"] for a in answerable_audits if a["search_recall_all_returned"] != 1.0],
        "gold_evidence_count": len(gold_diagnostics),
        "first_loss_by_stage": loss_counts,
        "gold_retained_in_final_context": sum(g["first_loss_stage"] is None for g in gold_diagnostics),
        "samples_with_fusion_loss": sorted({a["id"] for a in answerable_audits
                                              if any(g["first_loss_stage"] == "fusion" for g in a["gold"])}),
        "samples_with_rerank_loss": sorted({a["id"] for a in answerable_audits
                                             if any(g["first_loss_stage"] == "rerank" for g in a["gold"])}),
        "samples_with_context_loss": sorted({a["id"] for a in answerable_audits
                                              if any(g["first_loss_stage"] == "context" for g in a["gold"])}),
        "samples_with_provider_loss": sorted({a["id"] for a in answerable_audits
                                               if any(g["first_loss_stage"] == "provider" for g in a["gold"])}),
    }
    return {"note": "Exact ID/text presence only; alternative supporting evidence requires semantic review.",
            "summary": summary, "samples": audits}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("report", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--search-k", type=int, default=20)
    args = parser.parse_args()
    rows = {r["id"]: r for r in read_jsonl(args.dataset)}
    gold_ids = sorted({c for r in rows.values() for c in r.get("relevant_chunk_ids", [])})
    chunks = load_chunks(gold_ids)
    report = json.loads(args.report.read_text(encoding="utf-8"))
    args.output.write_text(json.dumps(build_audit(rows, report, chunks, args.search_k), ensure_ascii=False, indent=2),
                           encoding="utf-8")

if __name__ == "__main__":
    main()
