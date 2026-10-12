#!/usr/bin/env python3
from __future__ import annotations
import argparse,json,math
from pathlib import Path
from run_rag_eval import read_jsonl
STAGES=('vector','bm25','structured','fused','reranked','final_context','answer_candidates','prompt_evidence')
KS=(1,3,5,10,20,50)
def cid(c): return c.get('chunkId') or c.get('chunk_id')
def pid(c,parent): return c.get('parentChunkId') or c.get('parent_chunk_id') or parent.get(cid(c),cid(c))
def candidates(sample,stage):
 r=sample.get('response',sample); ret=r.get('retrieval',{})
 if stage in ('answer_candidates','prompt_evidence'):
  if stage in ret:return ret[stage]
  return r.get('evidence',[]) if stage=='prompt_evidence' else []
 if stage=='final_context': return ret.get('final_context',[])
 return ret.get('stage_candidates',{}).get(stage,[])
def metrics(rows,report,parent_map):
 samples={s['id']:s for s in report['samples']};out={'cases':len(rows),'stages':{}}
 for stage in STAGES:
  stage_out={}; available=[r for r in rows if candidates(samples.get(r['id'],{}),stage) is not None]
  for k in KS:
   hits=[];rec=[];phits=[];prec=[]
   for row in rows:
    cs=candidates(samples.get(row['id'],{}),stage); rel=set(row.get('relevant_chunk_ids',[])); relp={parent_map.get(x,x) for x in rel}; top=cs[:k]
    ids=[cid(c) for c in top]; pids=[pid(c,parent_map) for c in top]
    overlap=rel.intersection(ids); poverlap=relp.intersection(set(pids))
    hits.append(float(bool(overlap)));rec.append(len(overlap)/len(rel) if rel else 0);phits.append(float(bool(poverlap)));prec.append(len(poverlap)/len(relp) if relp else 0)
   n=len(rows);stage_out[f'hit@{k}']=sum(hits)/n if n else None;stage_out[f'recall@{k}']=sum(rec)/n if n else None;stage_out[f'parent_hit@{k}']=sum(phits)/n if n else None;stage_out[f'parent_recall@{k}']=sum(prec)/n if n else None
  rr=[];nd=[];prr=[];pnd=[]
  for row in rows:
   cs=candidates(samples.get(row['id'],{}),stage);rel=set(row.get('relevant_chunk_ids',[]));relp={parent_map.get(x,x) for x in rel};ids=[cid(c) for c in cs[:5]];pids=[pid(c,parent_map) for c in cs[:5]]
   rr.append(next((1/(i+1) for i,x in enumerate(ids) if x in rel),0));prr.append(next((1/(i+1) for i,x in enumerate(pids) if x in relp),0))
   top=cs[:10]; ids10=[cid(c) for c in top]; pids10=[pid(c,parent_map) for c in top]
   dcg=sum(1/math.log2(i+2) for i,x in enumerate(ids10) if x in rel); idcg=sum(1/math.log2(i+2) for i in range(min(len(rel),10)));nd.append(dcg/idcg if idcg else 0)
   pdcg=sum(1/math.log2(i+2) for i,x in enumerate(pids10) if x in relp); pidcg=sum(1/math.log2(i+2) for i in range(min(len(relp),10)));pnd.append(pdcg/pidcg if pidcg else 0)
  stage_out['mrr@5']=sum(rr)/len(rr);stage_out['parent_mrr@5']=sum(prr)/len(prr);stage_out['ndcg@10']=sum(nd)/len(nd);stage_out['parent_ndcg@10']=sum(pnd)/len(pnd);stage_out['available_cases']=len(available)
  out['stages'][stage]=stage_out
 return out
def main():
 ap=argparse.ArgumentParser();ap.add_argument('dataset',type=Path);ap.add_argument('report',type=Path);ap.add_argument('chunks',type=Path);ap.add_argument('--output',required=True,type=Path);a=ap.parse_args()
 rows=[r for r in read_jsonl(a.dataset) if r.get('type')!='unanswerable'];parent={}
 for line in a.chunks.read_text(encoding='utf8').splitlines():
  if line.strip():
   x=json.loads(line);parent[x.get('chunk_id')]=x.get('parent_chunk_id') or x.get('chunk_id')
 rep=json.loads(a.report.read_text(encoding='utf8'));a.output.write_text(json.dumps(metrics(rows,rep,parent),ensure_ascii=False,indent=2),encoding='utf8')
if __name__=='__main__':main()
