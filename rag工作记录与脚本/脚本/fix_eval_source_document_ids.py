import argparse
import json
import subprocess
from pathlib import Path


def run_psql(chunk_ids: list[str]) -> dict[str, tuple[str, str]]:
    if not chunk_ids:
        return {}
    values = ",".join("'" + chunk_id.replace("'", "''") + "'" for chunk_id in sorted(set(chunk_ids)))
    sql = (
        "select c.chunk_id, d.document_id, d.source_file "
        "from document_chunk c join document d on d.id=c.document_id "
        f"where c.chunk_id in ({values}) order by c.chunk_id"
    )
    completed = subprocess.run(
        [
            "docker", "exec", "postgres", "psql", "-U", "aiagent", "-d", "aiagent",
            "-t", "-A", "-F", "\t", "-c", sql,
        ],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    mapping: dict[str, tuple[str, str]] = {}
    for line in completed.stdout.splitlines():
        parts = line.split("\t", 2)
        if len(parts) == 3:
            mapping[parts[0]] = (parts[1], parts[2])
    return mapping


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("input", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()

    rows = [json.loads(line) for line in args.input.read_text(encoding="utf-8").splitlines() if line.strip()]
    chunk_ids = [chunk_id for row in rows for chunk_id in row.get("relevant_chunk_ids", [])]
    chunk_to_doc = run_psql(chunk_ids)

    fixed = 0
    ambiguous = []
    missing = []
    for row in rows:
        docs = {chunk_to_doc[chunk_id][0] for chunk_id in row.get("relevant_chunk_ids", []) if chunk_id in chunk_to_doc}
        if not docs:
            missing.append(row.get("id"))
            continue
        if len(docs) > 1:
            ambiguous.append({"id": row.get("id"), "document_ids": sorted(docs)})
            continue
        doc_id = next(iter(docs))
        if row.get("source_document_id") != doc_id:
            row["source_document_id"] = doc_id
            fixed += 1

    args.output.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in rows) + "\n", encoding="utf-8")
    print(json.dumps({
        "input_rows": len(rows),
        "fixed_rows": fixed,
        "ambiguous_rows": ambiguous,
        "missing_gold_chunks": missing,
        "output": str(args.output),
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
