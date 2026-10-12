"""Offline generation robustness experiment; never mutates the production corpus.

Reuse production prompts, then inject real database chunks. Calls the configured
dialogue model/protocol directly, so this measures the generation stage, not API routing.
"""
import argparse
import copy
import json
import os
import re
import subprocess
import time
import urllib.request
from pathlib import Path
from uuid import UUID

from run_rag_eval import read_jsonl


def load_chunks(ids):
    values = ",".join("'" + str(UUID(value)) + "'" for value in sorted(set(ids)))
    sql = f"""SELECT json_build_object(
      'chunkId',c.chunk_id,'documentId',d.document_id,'sourceFile',d.source_file,
      'location',c.location,'content',c.content,'parentChunkId',p.chunk_id,
      'parentContent',COALESCE(p.content,''))
      FROM document_chunk c JOIN document d ON d.id=c.document_id
      LEFT JOIN document_chunk p ON p.id=c.parent_chunk_id
      WHERE c.chunk_id IN ({values});"""
    result = subprocess.run(["docker", "exec", "postgres", "psql", "-U", "aiagent",
                             "-d", "aiagent", "-At", "-c", sql],
                            capture_output=True, encoding="utf-8", check=True)
    chunks = [json.loads(line) for line in result.stdout.splitlines() if line.strip()]
    if {c["chunkId"] for c in chunks} != set(ids):
        raise ValueError("Missing distractor chunks; cannot silently skip")
    return {c["chunkId"]: c for c in chunks}


def inject(prompt, chunks, position):
    instructions, raw = prompt.split("\nINPUT_JSON:\n", 1)
    data = json.loads(raw)
    additions = copy.deepcopy(chunks)
    existing = {e["id"] for e in data["evidence"]}
    next_id = 1
    for evidence in additions:
        while f"E{next_id}" in existing:
            next_id += 1
        evidence["id"] = f"E{next_id}"
        existing.add(evidence["id"])
    original = data["evidence"]
    data["evidence"] = additions + original if position == "prepend" else original + additions
    # No truncation: original facts and parent text must survive the intervention.
    rendered = instructions + "\nINPUT_JSON:\n" + json.dumps(data, ensure_ascii=False, separators=(",", ":"))
    if len(rendered) > 24000:
        raise ValueError("Injected prompt exceeds production character budget")
    return rendered, data["evidence"], [e["id"] for e in additions]


class ModelOutputError(ValueError):
    def __init__(self, message, raw, response_id):
        super().__init__(message)
        self.raw_response = raw
        self.response_id = response_id


def _parse_answer_arguments(raw, response_id):
    cleaned = re.sub(r"^\x60\x60\x60(?:json)?\s*|\s*\x60\x60\x60$", "", raw.strip())
    try:
        def reject_duplicates(pairs):
            result = {}
            for name, value in pairs:
                if name in result:
                    raise ValueError("Duplicate JSON key: " + name)
                result[name] = value
            return result
        parsed = json.loads(cleaned, object_pairs_hook=reject_duplicates)
    except (json.JSONDecodeError, ValueError) as error:
        raise ModelOutputError("Invalid JSON output", raw, response_id) from error
    if not isinstance(parsed, dict) or not isinstance(parsed.get("supported"), bool) or not isinstance(parsed.get("answer"), str) or not parsed["answer"].strip():
        raise ModelOutputError("Invalid answer contract", raw, response_id)
    if parsed.get("answerability") not in ("FULL", "PARTIAL", "NONE"):
        raise ModelOutputError("Invalid answerability", raw, response_id)
    return {"rawResponse": raw, "answer": parsed["answer"],
            "refusal": parsed["answerability"] == "NONE", "providerResponseId": response_id}


def normalize_bare_citations(answer, evidence):
    allowed = {item.get("id") for item in evidence}
    def replace(match):
        evidence_id = match.group(0)
        return f"[{evidence_id}]" if evidence_id in allowed else evidence_id
    return re.sub(r"(?<![\[A-Za-z0-9_])E\d+(?![\]A-Za-z0-9_])", replace, answer)


def call_model(prompt, key, api_url=None, model=None):
    api_url = api_url or os.getenv("WORKPRO_RAG_GENERATION_BASE_URL", "https://ahg.codes")
    model = model or os.getenv("WORKPRO_RAG_GENERATION_MODEL", "gpt-5.6-sol")
    answer_tool = {"type": "function", "name": "submit_answer",
        "description": "提交基于给定证据的最终回答与可答性判断",
        "parameters": {"type": "object", "properties": {
            "supported": {"type": "boolean"},
            "answerability": {"type": "string", "enum": ["FULL", "PARTIAL", "NONE"]},
            "answer": {"type": "string"}},
            "required": ["supported", "answerability", "answer"],
            "additionalProperties": False}}
    base = api_url.rstrip("/")
    if base.endswith("/responses"):
        url = base
        body = {"model": model, "max_output_tokens": 4096,
                "input": [{"role": "user", "content": [{"type": "input_text", "text": prompt}]}],
                "tools": [answer_tool],
                "tool_choice": {"type": "function", "name": "submit_answer"}}
    else:
        url = base + "/v1/chat/completions"
        function = {name: value for name, value in answer_tool.items() if name != "type"}
        body = {"model": model, "max_tokens": 4096, "temperature": 0.2,
                "messages": [{"role": "user", "content": prompt}],
                "tools": [{"type": "function", "function": function}],
                "tool_choice": {"type": "function", "function": {"name": "submit_answer"}}}
    request = urllib.request.Request(url,
        json.dumps(body).encode("utf-8"),
        {"Content-Type": "application/json", "Authorization": "Bearer " + key})
    with urllib.request.urlopen(request, timeout=90) as response:
        payload = json.load(response)
    if url.endswith("/responses"):
        if payload.get("status") != "completed":
            raise ValueError("Response not completed")
        raw = "".join(item.get("arguments", "") for item in payload.get("output", [])
                      if item.get("type") == "function_call" and item.get("name") == "submit_answer")
        result = _parse_answer_arguments(raw, payload.get("id"))
    else:
        raw = payload.get("choices", [{}])[0].get("message", {}).get("tool_calls", [{}])[0].get("function", {}).get("arguments")
        if raw is None:
            raw = payload.get("choices", [{}])[0].get("message", {}).get("content", "")
        result = _parse_answer_arguments(raw, payload.get("id"))
    result["usage"] = payload.get("usage")
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("dataset", type=Path)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--case-id", help="仅复测指定样本；使用不同输出文件保留原始失败")
    parser.add_argument("--api-url", default=os.getenv("WORKPRO_RAG_GENERATION_BASE_URL", "https://ahg.codes"))
    parser.add_argument("--model", default=os.getenv("WORKPRO_RAG_GENERATION_MODEL", "gpt-5.6-sol"))
    parser.add_argument("--variants", nargs="+", choices=("control", "prepend", "append"),
                        default=["control", "prepend", "append"])
    args = parser.parse_args()
    key = (os.getenv("WORKPRO_RAG_CHAT_API_KEY") or os.getenv("WORKPRO_LLM_API_KEY")
           or os.getenv("WORKPRO_RAG_GENERATION_API_KEY"))
    if not key:
        parser.error("Set WORKPRO_RAG_CHAT_API_KEY or WORKPRO_LLM_API_KEY for dialogue model generation")
    rows = [r for r in read_jsonl(args.dataset) if r["type"] == "context_confusion"]
    if args.case_id:
        rows = [r for r in rows if r["id"] == args.case_id]
        if not rows:
            parser.error("Unknown context_confusion case ID")
    baseline = {s["id"]: s for s in json.loads(args.baseline.read_text(encoding="utf-8"))["samples"]}
    chunks = load_chunks([value for row in rows for value in row["distractor_chunk_ids"]])
    report = {"scope": "offline_generation_intervention", "model": args.model, "api_url": args.api_url,
              "design": f"{len(rows)} cases x {','.join(args.variants)}; real distractor chunks with parents; original evidence intact",
              "review_status": "pending", "samples": []}
    for row in rows:
        source = baseline[row["id"]]["response"]
        for variant in args.variants:
            prompt = source["prompt"]
            evidence = source["evidence"]
            injected = []
            injection_error = None
            if variant != "control":
                try:
                    prompt, evidence, injected = inject(prompt, [chunks[c] for c in row["distractor_chunk_ids"]], variant)
                except ValueError as error:
                    injection_error = str(error)
            sample = {"id": row["id"] + ":" + variant, "case_id": row["id"], "variant": variant,
                      "query": row["query"], "expected": row["expected"], "prompt": prompt,
                      "evidence": evidence, "injected_ids": injected,
                      "already_present_chunk_ids": [c for c in row["distractor_chunk_ids"]
                          if c in {e["chunkId"] for e in source["evidence"]}]}
            started = time.monotonic()
            try:
                if injection_error:
                    sample["errorDetail"] = injection_error
                    raise ValueError(injection_error)
                sample.update(call_model(prompt, key, args.api_url, args.model))
                sample["answer"] = normalize_bare_citations(sample["answer"], evidence)
                cited = re.findall(r"\[(E\d+)\]", sample["answer"])
                mapped = bool(cited) and set(cited).issubset({e["id"] for e in evidence})
                sample["status"] = "OK" if sample["refusal"] or mapped else "INVALID_CITATIONS"
                sample["citation_ids_mapped"] = mapped
            except Exception as error:
                sample.update(status="ERROR", error=type(error).__name__)
                if isinstance(error, ModelOutputError):
                    sample.update(rawResponse=error.raw_response, providerResponseId=error.response_id,
                                  errorDetail=str(error))
            sample["latencyMs"] = round((time.monotonic() - started) * 1000)
            report["samples"].append(sample)
            args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(sample["id"], sample["status"], sample["latencyMs"], flush=True)


if __name__ == "__main__":
    main()
