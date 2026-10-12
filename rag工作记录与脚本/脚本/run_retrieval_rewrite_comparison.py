"""Paired full-corpus retrieval evaluation; shared queries run once, no generation.

Both arms reuse identical responses for unchanged questions. Changed questions
run back to back. Failed/degraded requests abort, never become zero recall.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

from build_retrieval_qrels import build_qrels, load_chunks
from evaluate_retrieval_qrels import evaluate
from run_rag_eval import read_jsonl
from run_retrieval_qrels_eval import retrieve


def live_sample(base_url: str, row: dict, backend_log: Path) -> dict:
    log_offset = backend_log.stat().st_size
    payload = retrieve(base_url, row, 50, True)
    with backend_log.open('rb') as log:
        log.seek(log_offset)
        new_log = log.read().decode('utf-8', errors='replace')
    if 'Cross-encoder rerank failed' in new_log or 'Cross-encoder reranked' not in new_log:
        raise RuntimeError(f"{row['id']}: real rerank success not confirmed in backend log")
    trace = payload.get('trace') or {}
    failures = trace.get('providerFailures')
    stages = payload.get('diagnostics') or {}
    if failures is None or failures:
        raise RuntimeError(f"{row['id']}: missing trace or degraded provider; comparison aborted")
    if not stages.get('vector') or not stages.get('reranked') or 'prompt_evidence' not in payload:
        raise RuntimeError(f"{row['id']}: missing vector/rerank/prompt data; comparison aborted")
    return {'id': row['id'], 'type': row['type'], 'query': row['query'], 'response': {
        'status': 'OK', 'trace': trace, 'prompt_chars': payload.get('prompt_chars'),
        'retrieval': {'stage_candidates': stages,
                      'prompt_evidence': payload['prompt_evidence'],
                      'answer_candidates': payload.get('answer_candidates', [])}}}


def save(path: Path, value: dict) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('old_dataset', type=Path)
    parser.add_argument('new_dataset', type=Path)
    parser.add_argument('chunks', type=Path)
    parser.add_argument('--output-dir', type=Path, required=True)
    parser.add_argument('--base-url', default='http://localhost:8080')
    parser.add_argument('--backend-log', type=Path,
                        default=Path(__file__).resolve().parents[2] / 'tmp/retrieval-v3-live.log')
    args = parser.parse_args()
    if not args.backend_log.exists():
        raise ValueError('Backend log missing. Start the backend with start_retrieval_eval.ps1 first.')
    old_rows, new_rows = read_jsonl(args.old_dataset), read_jsonl(args.new_dataset)
    if [r['id'] for r in old_rows] != [r['id'] for r in new_rows]:
        raise ValueError('Paired datasets must have the same ordered IDs')
    if len({r['id'] for r in old_rows}) != len(old_rows):
        raise ValueError('Duplicate IDs')
    changed = []
    for old, new in zip(old_rows, new_rows):
        if old['type'] != new['type'] or old['relevant_chunk_ids'] != new['relevant_chunk_ids']:
            raise ValueError('Type and gold chunk labels must stay fixed for this comparison')
        if old['query'] != new['query']:
            changed.append(old['id'])
    if changed != ['multi_hop-010']:
        raise ValueError(f'Unexpected changed queries: {changed}')
    chunks = load_chunks(args.chunks)
    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest = {'scope': 'full_corpus', 'created_at': datetime.now(timezone.utc).isoformat(),
                'changed_cases': changed, 'shared_queries_reused': len(old_rows) - len(changed),
                'top_k': 50, 'answer_context': True, 'generation_calls': 0,
                'dataset_sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                                   for p in (args.old_dataset, args.new_dataset)},
                'complete': False}
    save(args.output_dir / 'manifest.json', manifest)
    reports = [{'scope': 'full_corpus', 'samples': []} for _ in range(2)]
    for old, new in zip(old_rows, new_rows):
        baseline = live_sample(args.base_url, old, args.backend_log)
        variant = baseline if old['query'] == new['query'] else live_sample(args.base_url, new, args.backend_log)
        for report, sample in zip(reports, (baseline, variant)):
            report['samples'].append(sample)
        for name, report in zip(('old', 'new'), reports):
            save(args.output_dir / f'{name}-traces.json', report)
        print(f"{len(reports[0]['samples'])}/{len(old_rows)} {old['id']} OK", flush=True)
    results = []
    for name, rows, report in zip(('old', 'new'), (old_rows, new_rows), reports):
        result = evaluate(build_qrels(rows, chunks), report)
        results.append(result)
        save(args.output_dir / f'{name}-metrics.json', result)
    delta = {}
    for stage, old_metrics in results[0]['summary']['stages'].items():
        new_metrics = results[1]['summary']['stages'][stage]
        delta[stage] = {key: new_metrics[key] - value for key, value in old_metrics.items()
                        if isinstance(value, (int, float)) and isinstance(new_metrics.get(key), (int, float))}
    save(args.output_dir / 'delta.json', {
        'stage_deltas': delta,
        'interpretation': 'Only the evaluation question changed. This is not a retrieval algorithm improvement or a Ragas precision estimate.'})
    manifest['complete'] = True
    save(args.output_dir / 'manifest.json', manifest)


if __name__ == '__main__':
    main()
