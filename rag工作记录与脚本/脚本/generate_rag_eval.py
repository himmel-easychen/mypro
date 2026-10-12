#!/usr/bin/env python3
"""Build a 50-item RAG evaluation set from the draft JSONL.

The generator is deterministic by default.  It selects 10 base items and creates
10 items for each robustness family: multi-hop, comparison/mutation, context
confusion, and unanswerable/refusal.  An LLM adapter can be added later without
changing the output schema.
"""
import argparse, json, random, re
from pathlib import Path

TYPES = ["multi_hop", "compare_mutation", "context_confusion", "unanswerable"]

def load(path):
    return [json.loads(x) for x in Path(path).read_text(encoding="utf-8").splitlines() if x.strip()]

def norm_answer(x):
    # Draft items store the answer under expected.answer; accept both shapes.
    value = x.get("answer")
    if value is None and isinstance(x.get("expected"), dict):
        value = x["expected"].get("answer")
    return str(value or "").strip()

def base_item(x, i):
    y = dict(x)
    y["id"] = f"base-{i:03d}"
    y["difficulty"] = "L1"
    y["answerable"] = True
    y["source_item_id"] = x.get("id")
    y.setdefault("scoring", {"answer": 0.7, "evidence": 0.2, "qualification": 0.1})
    return y

def generated(source, gid, typ, query, answer, chunks, **extra):
    return {"id": gid, "source_document_id": source.get("source_document_id"),
            "source_document_pk": source.get("source_document_pk"),
            "source_file": source.get("source_file"), "query": query, "type": typ,
            "split": "gold", "difficulty": "L2" if typ != "unanswerable" else "L4",
            "answerable": typ != "unanswerable",
            "expected": {"answer": answer}, "relevant_chunk_ids": chunks,
            "source_item_id": source.get("id"),
            "scoring": {"answer": 0.5, "evidence": 0.3, "qualification": 0.2}, **extra}

def make_samples(items, n, seed, corpus=None):
    rng = random.Random(seed)
    pool = items[:]
    rng.shuffle(pool)
    out = []
    numeric_pool = [x for x in pool if re.search(r"[-+]?\d+(?:\.\d+)?", norm_answer(x))]
    for i in range(n):
        a = pool[i % len(pool)]
        b = pool[(i + 1) % len(pool)]
        # When a corpus is supplied, rotate source metadata so generated cases
        # are not all attributed to the seed PDF.
        if corpus:
            meta = corpus[i % len(corpus)]
            a = {**a, **{k: meta[k] for k in ("source_document_id", "source_document_pk", "source_file") if k in meta},
                 "relevant_chunk_ids": meta.get("relevant_chunk_ids", a.get("relevant_chunk_ids", []))}
        aid = a.get("relevant_chunk_ids", [])[:1]
        bid = b.get("relevant_chunk_ids", [])[:1]
        chunks = list(dict.fromkeys(aid + bid))
        av, bv = norm_answer(a), norm_answer(b)
        out.append(generated(a, f"multi-hop-{i+1:03d}", "multi_hop",
            natural_multihop(a, b),
            f"{av}；{bv}", chunks, required_hops=2,
            evidence_requirements=[{"chunk_ids": aid, "supports": ["first_fact"]}, {"chunk_ids": bid, "supports": ["second_fact"]}]))
        # Comparison is only valid for compatible numeric answers.
        if numeric_pool:
            a = numeric_pool[i % len(numeric_pool)]
            b = numeric_pool[(i + 1) % len(numeric_pool)]
            aid, bid = a.get("relevant_chunk_ids", [])[:1], b.get("relevant_chunk_ids", [])[:1]
            chunks = list(dict.fromkeys(aid + bid)); av, bv = norm_answer(a), norm_answer(b)
        nums = [re.search(r"[-+]?\d+(?:\.\d+)?", v) for v in (av, bv)]
        if nums and all(nums):
            x, y = float(nums[0].group()), float(nums[1].group())
            relation = "高于" if x < y else ("低于" if x > y else "等于")
            q = f"对比以下两项，哪一项的数值更高：{a['query']}；{b['query']}？"
            ans = f"第二项（{bv}）{relation}第一项（{av}）。" if x != y else f"两项相等，均为{av}。"
        else:
            q, ans = f"请分别说明：{a['query']}；{b['query']}", f"第一项：{av}；第二项：{bv}"
        out.append(generated(a, f"compare-{i+1:03d}", "compare_mutation", q, ans, chunks, operation="compare", entities=[a['id'], b['id']]))
        out.append(generated(a, f"confusion-{i+1:03d}", "context_confusion",
            a['query'], av, aid,
            distractor_chunk_ids=bid, distractor_reason="相邻/相似条目，属性不同"))
        out.append(generated(a, f"reject-{i+1:03d}", "unanswerable",
            "该招标文件公布的中标供应商名称是什么？", "未提及", [],
            refusal_reason="文档是招标文件，未公布中标结果", refusal_policy="明确拒答，不得猜测"))
    return out

def natural_multihop(a, b):
    # Human-style joining; production deployments may replace this with an LLM rewriter.
    qa, qb = a['query'], b['query']
    if '名称' in qa and '编号' in qb: return '请问这个项目的全称是什么？对应的项目采购编号又是多少？'
    if '标的' in qa and '金额' in qb: return '请问相关标的对应的区域和预计采购金额分别是多少？'
    return f"请问{qa.rstrip('？')}，同时{qb.rstrip('？')}？"

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input", type=Path)
    ap.add_argument("output", type=Path)
    ap.add_argument("--corpus-jsonl", type=Path, help="JSONL exported from document/document_chunk tables")
    ap.add_argument("--seed", type=int, default=20260912)
    args = ap.parse_args()
    draft = load(args.input)
    corpus = load(args.corpus_jsonl) if args.corpus_jsonl else None
    if corpus and len({x.get('source_document_id') for x in corpus}) < 2:
        raise SystemExit("corpus-jsonl must contain at least two documents")
    # Stratified-ish deterministic selection: preserve coverage of types, then fill.
    chosen, seen = [], set()
    for t in ["semantic", "identifier", "count", "table_numeric", "numeric_rule", "date_time", "field_lookup", "boolean", "formula", "table_field"]:
        for x in draft:
            if x.get("type") == t and x["id"] not in seen:
                chosen.append(x); seen.add(x["id"]); break
    chosen = (chosen + [x for x in draft if x["id"] not in seen])[:10]
    result = [base_item(x, i+1) for i, x in enumerate(chosen)] + make_samples(chosen, 10, args.seed, corpus)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(json.dumps(x, ensure_ascii=False) for x in result) + "\n", encoding="utf-8")
    print(f"wrote {len(result)} items: 10 base + 10 each of {', '.join(TYPES)}")

if __name__ == "__main__": main()
