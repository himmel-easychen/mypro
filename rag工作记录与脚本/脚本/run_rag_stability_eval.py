"""Repeat fixed regression cases, preserving every attempt and response."""
import argparse
import concurrent.futures
import json
from pathlib import Path
from run_rag_eval import read_jsonl, http_answer

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--repeats", type=int, default=10)
    args = parser.parse_args()
    rows = {row["id"]: row for row in read_jsonl(args.dataset)}
    cases = [("basic-004", False), ("multi_hop-008", False),
             ("compare_mutation-003", False), ("multi_hop-010", True),
             ("compare_mutation-004", True), ("compare_mutation-010", True)]
    report = {"review_status": "pending", "samples": []}
    def run(case, full, repeat):
        try:
            response = http_answer(args.base_url, rows[case], full_corpus=full)
        except Exception as error:
            response = {"status": "ERROR", "error": type(error).__name__}
        return {"id": case, "full_corpus": full, "repeat": repeat, "response": response}
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        tasks = [pool.submit(run, case, full, repeat) for repeat in range(1, args.repeats + 1)
                 for case, full in cases]
        for task in concurrent.futures.as_completed(tasks):
            sample = task.result()
            report["samples"].append(sample)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            print(sample["id"], sample["repeat"], sample["response"].get("status"), flush=True)

if __name__ == "__main__":
    main()
