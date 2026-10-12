#!/usr/bin/env python3
"""Generate a 50-case RAG evaluation set from all database documents."""
import argparse
import json
import os
import random
import urllib.error
import urllib.request
import re
import time
import http.client
from pathlib import Path

CASE_TYPES = ("multi_hop", "compare_mutation", "context_confusion", "unanswerable")

def load_jsonl(path):
    rows = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        row = json.loads(line)
        ids = row.get("relevant_chunk_ids") or []
        if not row.get("chunk_id") and ids:
            row["chunk_id"] = ids[0]
        rows.append(row)
    return rows

def codex_key():
    key = os.getenv("RAG_LLM_API_KEY") or os.getenv("AGICORE_LLM_API_KEY")
    if key:
        return key
    auth = Path(os.getenv("USERPROFILE", "")) / ".codex" / "auth.json"
    if auth.exists():
        try:
            value = json.loads(auth.read_text(encoding="utf-8")).get("OPENAI_API_KEY")
            if value:
                return value
        except (OSError, json.JSONDecodeError):
            pass
    return None

def response_text(data):
    if isinstance(data.get("output_text"), str) and data["output_text"].strip():
        return data["output_text"].strip()
    parts = []
    for item in data.get("output", []):
        for content in item.get("content", []) if isinstance(item, dict) else []:
            text = content.get("text") if isinstance(content, dict) else None
            if isinstance(text, str):
                parts.append(text)
    return "\n".join(parts).strip()

def call(prompt, url, key, model, max_attempts=5):
    responses = url.rstrip("/").endswith("/responses")
    body = ({"model": model, "input": prompt, "temperature": 0.2, "max_output_tokens": 1800}
            if responses else {"model": model, "temperature": 0.2, "messages": [
                {"role": "system", "content": "你是严谨的RAG评测集生成器，只返回JSON数组。"},
                {"role": "user", "content": prompt}]})
    req = urllib.request.Request(url, json.dumps(body, ensure_ascii=False).encode("utf-8"),
                                {"Content-Type": "application/json", "Authorization": "Bearer " + key,
                                 "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) RAG-Eval-Generator/1.0"})
    last_error = None
    for attempt in range(max_attempts):
        try:
            with urllib.request.urlopen(req, timeout=180) as response:
                data = json.load(response)
            text = response_text(data) if responses else data["choices"][0]["message"]["content"]
            return json.loads(text.strip().removeprefix("```json").removesuffix("```").strip())
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", errors="replace")[:500]
            last_error = RuntimeError(f"模型HTTP {error.code}: {detail}")
            # Retry transient gateway/rate-limit failures; fail fast on auth/bad request.
            if error.code not in (408, 425, 429, 500, 502, 503, 504):
                raise last_error from error
        except (urllib.error.URLError, TimeoutError, http.client.RemoteDisconnected,
                ConnectionResetError, json.JSONDecodeError) as error:
            last_error = error
        if attempt + 1 < max_attempts:
            time.sleep(min(30, 2 ** attempt))
    raise RuntimeError(f"模型调用在{max_attempts}次尝试后仍失败: {last_error}") from last_error

def choose_chunks(docs, rng, count=3):
    def substantive(c):
        text = re.sub(r"\[.*?页\]|\|block:\d+", "", c.get("content", ""))
        return len(text.strip()) >= 120
    candidates = [doc for doc in docs if sum(substantive(c) for c in doc) >= count] or docs
    group = rng.choice(candidates)
    usable = [c for c in group if substantive(c)] or group
    return usable[:count] if len(usable) <= count else rng.sample(usable, count)

def build_prompt(chunks):
    context = "\n\n".join(
        f"Chunk {i} | chunk_id={c['chunk_id']} | source_document_id={c['source_document_id']} | "
        f"source_file={c.get('source_file', '')} | location={c.get('location', '')}\n{c.get('content', '')[:7000]}"
        for i, c in enumerate(chunks, 1))
    return f"""你是RAG系统的评测集设计师。下面是来自数据库的真实文档Chunk。
严格生成4条JSON对象，各生成1条：multi_hop、compare_mutation、context_confusion、unanswerable。
query必须是自然的真实用户问法，不得出现Chunk、上下文、测试、检索、干扰项等框架词。
multi_hop必须综合至少两个Chunk中的两个业务事实，不能只复述项目名称、页码或文件名；compare_mutation必须比较同一维度的业务属性（如金额、数量、期限、比例、服务范围），题目和答案都要完成实际比较，禁止比较页码先后、文本长短、文件名或不可比维度；context_confusion必须有一个Chunk明确支持目标答案，另一个Chunk只在主题或实体上相似但不能支持答案；unanswerable与主题相关但无法由文本推出，expected.answer严格为“未提及”。
relevant_chunk_ids和distractor_chunk_ids只能使用下面列出的chunk_id。只返回JSON数组，不要Markdown。
每项格式：{{"type":"...","query":"...","expected":{{"answer":"..."}},"relevant_chunk_ids":["..."],"distractor_chunk_ids":["..."],"refusal_reason":"..."}}

真实文档内容：
{context}"""

def validate_case(case, allowed, expected_type):
    if not isinstance(case, dict) or case.get("type") != expected_type or not isinstance(case.get("query"), str):
        return False
    expected = case.get("expected")
    if not isinstance(expected, dict) or not isinstance(expected.get("answer"), str):
        return False
    if not set(case.get("relevant_chunk_ids", [])) <= allowed or not set(case.get("distractor_chunk_ids", [])) <= allowed:
        return False
    return expected_type != "unanswerable" or expected["answer"] == "未提及"

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("corpus", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--base-draft", type=Path)
    parser.add_argument("--per-type", type=int, default=10)
    parser.add_argument("--seed", type=int, default=20260912)
    parser.add_argument("--prompts-only", action="store_true")
    parser.add_argument("--api-url", default=os.getenv("RAG_LLM_URL", "https://ahg.codes/v1/responses"))
    parser.add_argument("--model", default=os.getenv("RAG_LLM_MODEL", "gpt-5.5"))
    args = parser.parse_args()
    rows = load_jsonl(args.corpus)
    by_doc = {}
    for row in rows:
        if row.get("source_document_id") and row.get("content", "").strip():
            by_doc.setdefault(row["source_document_id"], []).append(row)
    docs = list(by_doc.values())
    if len(docs) < 2:
        raise SystemExit("需要至少两个含真实文本的数据库文档")
    rng = random.Random(args.seed); rng.shuffle(docs)
    by_ext = {}
    for doc in docs:
        name = doc[0].get("source_file", "").lower()
        ext = Path(name.split("?")[0]).suffix or ".unknown"
        by_ext.setdefault(ext, []).append(doc)
    source_pools = [pool for pool in by_ext.values() if any(len(c.get("content", "")) >= 120 for d in pool for c in d)]
    if not source_pools:
        source_pools = [docs]
    key = codex_key()
    if not args.prompts_only and not key:
        raise SystemExit("未找到 Chat 链路认证；可先用 --prompts-only 检查提示词")
    generated = []
    for batch in range(args.per_type):
        pool = source_pools[batch % len(source_pools)]
        chunks = choose_chunks(pool, rng, 3); prompt = build_prompt(chunks)
        if args.prompts_only:
            generated.append({"batch": batch + 1, "prompt": prompt}); continue
        allowed = {chunk["chunk_id"] for chunk in chunks}
        by_type = {}
        allowed = {chunk["chunk_id"] for chunk in chunks}
        for attempt in range(3):
            retry_prompt = prompt if attempt == 0 else prompt + "\n上一次输出未通过校验。请严格使用四个type值：multi_hop、compare_mutation、context_confusion、unanswerable，并确保所有ID来自上文。"
            raw = call(retry_prompt, args.api_url, key, args.model)
            by_type = {case.get("type"): case for case in raw if isinstance(case, dict)}
            aliases = {"semantic": "multi_hop", "compare": "compare_mutation", "confusion": "context_confusion", "negative": "unanswerable"}
            for alias, target in aliases.items():
                if target not in by_type and alias in by_type:
                    by_type[target] = dict(by_type[alias], type=target)
            if all(validate_case(by_type.get(t), allowed, t) for t in CASE_TYPES):
                break
        for case_type in CASE_TYPES:
            case = by_type.get(case_type)
            if not validate_case(case, allowed, case_type):
                raise SystemExit(f"第{batch + 1}批{case_type}未通过结构校验")
            case["id"] = f"{case_type}-{batch + 1:03d}"; case["split"] = "gold"
            case["source_document_ids"] = sorted({c["source_document_id"] for c in chunks})
            case["source_files"] = sorted({c.get("source_file", "") for c in chunks if c.get("source_file")})
            case["generated_from_chunk_ids"] = sorted(allowed); generated.append(case)
    base = []
    if args.base_draft:
        for index, item in enumerate(load_jsonl(args.base_draft)[:10], 1):
            item = dict(item); item["id"] = f"base-{index:03d}"; item["split"] = "basic"; item.pop("review_status", None); base.append(item)
    result = base + generated
    args.output.write_text("\n".join(json.dumps(item, ensure_ascii=False) for item in result) + "\n", encoding="utf-8")
    print(f"wrote {len(result)} records ({len(base)} basic + {len(generated)} generated)")

if __name__ == "__main__":
    main()
