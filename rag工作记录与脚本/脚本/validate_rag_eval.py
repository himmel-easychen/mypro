#!/usr/bin/env python3
"""Validate the first-version RAG evaluation set.

The validator intentionally checks only stable, local facts:
- the JSONL file is well-formed;
- case IDs are unique;
- the enhanced sample groups contain 10 cases each;
- unanswerable cases use the canonical refusal answer;
- every relevant chunk id exists in the exported corpus JSONL.

Runtime embedding audit is kept separate because it depends on a running backend
or direct database access. Task execution should run this validator first, then
call /api/rag/embedding-audit as the environment check.
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any


REQUIRED_ENHANCED_TYPES = {
    "multi_hop": 10,
    "compare_mutation": 10,
    "context_confusion": 10,
    "unanswerable": 10,
}
CANONICAL_REFUSAL_ANSWER = "未提及"


def read_jsonl(path: Path) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    with path.open("r", encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            text = line.strip()
            if not text:
                continue
            try:
                row = json.loads(text)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_number}: invalid JSON: {exc.msg}") from exc
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{line_number}: expected JSON object")
            rows.append(row)
    return rows


def load_chunk_ids(chunks_path: Path) -> set[str]:
    chunk_ids: set[str] = set()
    for row in read_jsonl(chunks_path):
        for chunk_id in row.get("relevant_chunk_ids", []):
            if isinstance(chunk_id, str) and chunk_id:
                chunk_ids.add(chunk_id)
        direct_chunk_id = row.get("chunk_id")
        if isinstance(direct_chunk_id, str) and direct_chunk_id:
            chunk_ids.add(direct_chunk_id)
    return chunk_ids


def validate_records(eval_path: Path, chunks_path: Path, expected_unanswerable: int = 10) -> list[str]:
    errors: list[str] = []

    if not eval_path.exists():
        return [f"missing eval file: {eval_path}"]
    if not chunks_path.exists():
        return [f"missing chunks file: {chunks_path}"]

    try:
        records = read_jsonl(eval_path)
        corpus_chunk_ids = load_chunk_ids(chunks_path)
    except ValueError as exc:
        return [str(exc)]

    if len(records) != 50:
        errors.append(f"expected 50 records, got {len(records)}")

    ids = [record.get("id") for record in records]
    if any(not isinstance(case_id, str) or not case_id for case_id in ids):
        errors.append("every record must have a non-empty string id")
    duplicates = sorted(case_id for case_id, count in Counter(ids).items() if count > 1)
    for duplicate in duplicates:
        errors.append(f"duplicate id: {duplicate}")

    type_counts = Counter(record.get("type") for record in records)
    for sample_type, expected_count in REQUIRED_ENHANCED_TYPES.items():
        if sample_type == "unanswerable":
            expected_count = expected_unanswerable
        actual_count = type_counts.get(sample_type, 0)
        if actual_count != expected_count:
            errors.append(f"expected {expected_count} {sample_type} records, got {actual_count}")

    for index, record in enumerate(records, start=1):
        case_id = record.get("id", f"line-{index}")
        expected = record.get("expected")
        if not isinstance(expected, dict):
            errors.append(f"{case_id}: expected must be an object")
            expected = {}
        answer = expected.get("answer")
        if not isinstance(answer, str) or not answer.strip():
            errors.append(f"{case_id}: expected.answer must be a non-empty string")
        if record.get("type") == "unanswerable" and answer != CANONICAL_REFUSAL_ANSWER:
            errors.append(f"{case_id}: unanswerable expected.answer must be {CANONICAL_REFUSAL_ANSWER}")

        relevant_chunk_ids = record.get("relevant_chunk_ids")
        if not isinstance(relevant_chunk_ids, list) or not relevant_chunk_ids:
            errors.append(f"{case_id}: relevant_chunk_ids must be a non-empty array")
            continue
        for chunk_id in relevant_chunk_ids:
            if not isinstance(chunk_id, str) or not chunk_id:
                errors.append(f"{case_id}: relevant_chunk_ids must contain non-empty strings")
            elif chunk_id not in corpus_chunk_ids:
                errors.append(f"{case_id}: missing relevant_chunk_id: {chunk_id}")

    return errors


def default_chunks_path(eval_path: Path) -> Path:
    candidate = eval_path.parent / "all-document-chunks.jsonl"
    if candidate.exists():
        return candidate
    return Path("wd/all-document-chunks.jsonl")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Validate a RAG eval JSONL file against exported chunks.")
    parser.add_argument("eval_path", type=Path)
    parser.add_argument("--chunks", type=Path, default=None)
    parser.add_argument("--expected-unanswerable", type=int, default=10, choices=range(51))
    args = parser.parse_args(argv)

    chunks_path = args.chunks or default_chunks_path(args.eval_path)
    errors = validate_records(args.eval_path, chunks_path, args.expected_unanswerable)
    if errors:
        for error in errors:
            print(f"ERROR {error}", file=sys.stderr)
        return 1

    print("VALID 50 records")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
