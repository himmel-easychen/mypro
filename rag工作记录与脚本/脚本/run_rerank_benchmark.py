"""Fresh full-set rerank benchmark. No answer calls or historical reuse."""
import argparse
import hashlib
import json
import math
import time
from datetime import datetime, timezone
from pathlib import Path

from run_rag_eval import read_jsonl
from run_retrieval_qrels_eval import retrieve


def score(row, ranked):
    ids = [c['chunkId'] for c in ranked]
    if len(ids) != len(set(ids)):
        raise ValueError('Duplicate ranked chunk IDs')
    gold = set(row['relevant_chunk_ids'])
    if not gold:
        raise ValueError('Answerable sample without gold')
    dcg = sum(1 / math.log2(i + 2) for i, cid in enumerate(ids[:10]) if cid in gold)
    ideal = sum(1 / math.log2(i + 2) for i in range(min(10, len(gold))))
    return {'recall@5': len(gold.intersection(ids[:5])) / len(gold),
            'recall@20': len(gold.intersection(ids[:20])) / len(gold),
            'mrr@5': next((1 / (i + 1) for i, cid in enumerate(ids[:5]) if cid in gold), 0),
            'ndcg@10': dcg / ideal}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('dataset', type=Path)
    ap.add_argument('--output', type=Path, required=True)
    ap.add_argument('--base-url', default='http://localhost:8080')
    ap.add_argument('--backend-log', type=Path, default=Path('tmp/retrieval-v3-live.log'))
    ap.add_argument('--enable-query-transform', action='store_true')
    a = ap.parse_args()
    if a.output.exists():
        raise ValueError('Use a new output path; historical results are not reused or overwritten')
    rows = read_jsonl(a.dataset)
    if len({r['id'] for r in rows}) != len(rows):
        raise ValueError('Duplicate sample IDs')
    report = {'created_at': datetime.now(timezone.utc).isoformat(), 'scope': 'full_corpus',
              'dataset': str(a.dataset), 'dataset_sha256': hashlib.sha256(a.dataset.read_bytes()).hexdigest(),
              'disable_query_transform': not a.enable_query_transform, 'top_k': 50,
              'answer_context': True, 'stage': 'reranked', 'aggregation': 'macro per answerable case',
              'matching': 'exact gold chunk ID; binary nDCG; zero RR if no gold in first five',
              'generation_calls': 0, 'historical_samples_reused': 0, 'expected_cases': len(rows),
              'complete': False, 'samples': []}
    a.output.parent.mkdir(parents=True, exist_ok=True)
    def save():
        a.output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
    save()
    for row in rows:
        offset = a.backend_log.stat().st_size
        start = time.monotonic()
        sample = {'id': row['id'], 'type': row['type'], 'query': row['query']}
        try:
            payload = retrieve(a.base_url, row, 50, True, not a.enable_query_transform)
            with a.backend_log.open('rb') as handle:
                handle.seek(offset)
                log = handle.read().decode('utf8', errors='replace')
            sample['payload'] = payload
            trace = payload.get('trace') or {}
            if trace.get('providerFailures') != {}:
                raise ValueError('Missing provider trace or provider failure')
            if 'Cross-encoder rerank failed' in log or 'Cross-encoder reranked' not in log:
                raise ValueError('Real reranker success not verified')
            stages = payload.get('diagnostics') or {}
            if not stages.get('vector') or not stages.get('reranked'):
                raise ValueError('Missing vector or reranked candidates')
            sample['status'] = 'OK'
            if row['type'] != 'unanswerable':
                sample['metrics'] = score(row, stages['reranked'])
        except Exception as exc:
            sample['status'] = 'ERROR'
            sample['error'] = f'{type(exc).__name__}: {exc}'
            report['samples'].append(sample)
            save()
            raise
        sample['latency_ms'] = round((time.monotonic() - start) * 1000)
        report['samples'].append(sample)
        save()
        print(f"{len(report['samples'])}/{len(rows)} {row['id']} OK {sample['latency_ms']}ms", flush=True)
    measured = [s['metrics'] for s in report['samples'] if 'metrics' in s]
    report['answerable_cases'] = len(measured)
    report['unanswerable_cases'] = len(rows) - len(measured)
    report['metrics'] = {k: sum(m[k] for m in measured) / len(measured) for k in measured[0]}
    report['complete'] = True
    save()
    print(json.dumps(report['metrics']), flush=True)


if __name__ == '__main__':
    main()
