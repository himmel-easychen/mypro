#!/usr/bin/env python3
"""Run one diagnostics request per fixed case and save retrieval-only traces."""
from __future__ import annotations

import argparse
import json
import time
import urllib.parse
import urllib.request
from pathlib import Path
from run_rag_eval import read_jsonl


def retrieve(base_url: str, row: dict, top_k: int, full_corpus: bool,
             disable_query_transform: bool = False) -> dict:
    params = {"query": row["query"], "topK": top_k, "mode": "auto",
              "diagnostics": "true", "answerContext": "true"}
    params["disableQueryTransform"] = str(disable_query_transform).lower()
    if not full_corpus and row.get("source_document_id"):
        params["documentId"] = row["source_document_id"]
    url = base_url.rstrip("/") + "/api/rag/search?" + urllib.parse.urlencode(params)
    with urllib.request.urlopen(url, timeout=180) as response:
        return json.load(response)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--top-k", type=int, default=50)
    parser.add_argument("--output", type=Path, required=True)
    scope = parser.add_mutually_exclusive_group()
    # Full-corpus is the comparable evaluation scope. Document filtering is
    # an explicit diagnostic mode so it cannot silently inflate recall.
    scope.add_argument("--full-corpus", dest="full_corpus", action="store_true")
    scope.add_argument("--document-filtered", dest="full_corpus", action="store_false")
    parser.set_defaults(full_corpus=True)
    args = parser.parse_args()
    samples = []
    for row in read_jsonl(args.dataset):
        started = time.monotonic()
        try:
            payload = retrieve(args.base_url, row, args.top_k, args.full_corpus)
            response = {"retrieval": {"stage_candidates": payload.get("diagnostics", {}),
                                       "answer_candidates": payload.get("answer_candidates", []),
                                       "prompt_evidence": payload.get("prompt_evidence", [])},
                        "query_coverage": payload.get("query_coverage"),
                        "trace": payload.get("trace"),
                        "targeted_query_indices": payload.get("targeted_query_indices"),
                        "status": "OK"}
        except Exception as exc:
            response = {"retrieval": {"stage_candidates": {}}, "status": "ERROR",
                        "error": f"{type(exc).__name__}: {exc}"}
        response["latencyMs"] = round((time.monotonic() - started) * 1000)
        print(row["id"], response["status"], response["latencyMs"], flush=True)
        samples.append({"id": row["id"], "type": row.get("type"),
                        "query": row.get("query"), "response": response})
    args.output.write_text(json.dumps({"samples": samples, "top_k": args.top_k,
                                       "scope": "full_corpus" if args.full_corpus else "document_filtered"},
                                      ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
