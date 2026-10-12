#!/usr/bin/env python3
"""Build sparse evidence labels; gold chunks are not atomic facts.

Parent IDs are diagnostic only. Coverage of a prompt is checked against the
actual source text, not inferred from a shared parent ID.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from run_rag_eval import read_jsonl


def load_chunks(path: Path) -> dict[str, dict]:
    chunks = {}
    with path.open("r", encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            row = json.loads(line)
            chunk_id = row.get("chunk_id") or row.get("id")
            if chunk_id:
                chunks[chunk_id] = row
    return chunks


def build_qrels(rows: list[dict], chunks: dict[str, dict]) -> dict:
    cases = []
    for row in rows:
        facts = []
        for index, chunk_id in enumerate(dict.fromkeys(row.get("relevant_chunk_ids", [])), start=1):
            chunk = chunks.get(chunk_id, {})
            if not chunk.get("content"):
                raise ValueError(f"Missing source text for {row['id']}: {chunk_id}")
            parent_id = chunk.get("parent_chunk_id") or chunk_id
            facts.append({
                "fact_id": f"fact-{index}",
                "relevant_chunk_ids": [chunk_id],
                "relevant_parent_ids": [parent_id],
                "equivalence_group": None,
                "gold_text": chunk["content"],
                "document_id": chunk.get("source_document_id") or chunk.get("document_id"),
            })
        cases.append({
            "id": row["id"],
            "type": row.get("type"),
            "query": row.get("query"),
            "source_document_id": row.get("source_document_id"),
            "facts": facts,
            "atomic_facts": row.get("atomic_facts", []),
            "evaluation_notes": row.get("evaluation_notes", {}),
            "distractors": [{"chunk_id": cid, "text": chunks.get(cid, {}).get("content", "")}
                            for cid in row.get("distractor_chunk_ids", [])],
        })
    return {
        "version": "retrieval-qrels-evidence-v3",
        "description": "Sparse gold evidence, not exhaustive relevance or atomic fact labels. Unjudged candidates remain unknown.",
        "cases": cases,
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("chunks", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    rows = read_jsonl(args.dataset)
    qrels = build_qrels(rows, load_chunks(args.chunks))
    args.output.write_text(json.dumps(qrels, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
