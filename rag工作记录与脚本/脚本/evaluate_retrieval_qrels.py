#!/usr/bin/env python3
"""Sparse-label diagnostics. Never infer semantic precision from unjudged hits."""
from __future__ import annotations
import argparse
import json
import re
from pathlib import Path

STAGES = ('vector', 'bm25', 'structured', 'fused', 'reranked', 'final_context', 'answer_candidates', 'prompt_evidence')


def normalized(text: str) -> str:
    # Preserve negation, units, numbers, and punctuation.
    return re.sub(r'\s+', '', text or '')


def candidate_id(candidate: dict) -> str | None:
    return candidate.get('chunkId') or candidate.get('chunk_id')


def text_parts(candidate: dict) -> list[str]:
    return [normalized(candidate.get(key, '')) for key in ('content', 'content_preview', 'parentContent', 'parent_content') if candidate.get(key)]


def _stage_candidates(sample: dict, stage: str) -> list[dict] | None:
    response = sample.get('response', sample)
    retrieval = response.get('retrieval', {})
    if stage in ('answer_candidates', 'prompt_evidence'):
        if stage in retrieval:
            return retrieval[stage]
        if stage == 'prompt_evidence' and 'evidence' in response:
            return response['evidence']
        return None
    return retrieval.get('stage_candidates', {}).get(stage)


def exact_match(candidate: dict, fact: dict) -> bool:
    return candidate_id(candidate) in fact.get('relevant_chunk_ids', [])


def parent_match(candidate: dict, fact: dict) -> bool:
    parent = candidate.get('parentChunkId') or candidate.get('parent_chunk_id') or candidate_id(candidate)
    return exact_match(candidate, fact) or parent in fact.get('relevant_parent_ids', [])


def text_match(candidate: dict, fact: dict) -> bool:
    document = candidate.get('documentId') or candidate.get('document_id')
    if fact.get('document_id') and document and document != fact['document_id']:
        return False
    gold = normalized(fact.get('gold_text', ''))
    return bool(gold) and any(gold in part for part in text_parts(candidate))


def evaluate(qrels: dict, report: dict) -> dict:
    if qrels.get('version') not in {'retrieval-qrels-evidence-v2', 'retrieval-qrels-evidence-v3'}:
        raise ValueError('Rebuild qrels as v2/v3; v1 parent/equivalence labels are not text coverage.')
    samples = {sample['id']: sample for sample in report.get('samples', [])}
    cases = [case for case in qrels['cases'] if case.get('type') != 'unanswerable']
    summary = {'cases': len(cases), 'stages': {}}
    details = {}
    for stage in STAGES:
        measured, missing = [], []
        for case in cases:
            sample = samples.get(case['id'])
            if sample and (sample.get('query') != case.get('query') or sample.get('type') != case.get('type')):
                raise ValueError(f"Dataset mismatch: {case['id']}")
            candidates = _stage_candidates(sample, stage) if sample else None
            if candidates is None:
                missing.append(case['id'])
                continue
            facts = case.get('facts', [])
            measured.append({
                'id': case['id'], 'type': case['type'], 'facts': len(facts),
                'candidate_count': len(candidates),
                'exact_hits': sum(any(exact_match(c, f) for c in candidates) for f in facts),
                'parent_hits': sum(any(parent_match(c, f) for c in candidates) for f in facts),
                'text_hits': sum(any(text_match(c, f) for c in candidates) for f in facts) if stage == 'prompt_evidence' else None,
                'missing_text_evidence': [f['fact_id'] for f in facts if not any(text_match(c, f) for c in candidates)] if stage == 'prompt_evidence' else None,
            })
        facts = sum(m['facts'] for m in measured)
        metrics = {
            'measured_cases': len(measured), 'missing_cases': missing, 'gold_evidence_count': facts,
            'candidate_count': sum(m['candidate_count'] for m in measured),
            'exact_chunk_recall': sum(m['exact_hits'] for m in measured) / facts if facts else None,
            'parent_id_recall_diagnostic': sum(m['parent_hits'] for m in measured) / facts if facts else None,
            'candidate_precision': None,
            'precision_unavailable_reason': 'Sparse positives; unjudged candidates are not negatives.',
        }
        if stage == 'prompt_evidence':
            metrics.update({
                'gold_text_coverage': sum(m['text_hits'] for m in measured) / facts if facts else None,
                'complete_gold_text_cases': sum(m['text_hits'] == m['facts'] for m in measured),
                'complete_gold_text_rate': sum(m['text_hits'] == m['facts'] for m in measured) / len(measured) if measured else None,
            })
        summary['stages'][stage] = metrics
        details[stage] = measured
    return {'qrels_version': qrels['version'], 'scope': report.get('scope', 'unrecorded'),
            'limitations': 'Gold text coverage is a conservative source-span proxy, not semantic fact recall or Ragas Context Recall.',
            'summary': summary, 'details': details}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('qrels', type=Path)
    parser.add_argument('report', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    qrels = json.loads(args.qrels.read_text(encoding='utf-8'))
    report = json.loads(args.report.read_text(encoding='utf-8'))
    args.output.write_text(json.dumps(evaluate(qrels, report), ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
