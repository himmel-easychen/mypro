"""Launch a dedicated evaluation backend using memory-only credentials."""
import getpass
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import time
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[2]


def api(url, key, body=None):
    request = urllib.request.Request(url, data=json.dumps(body).encode() if body else None,
        headers={'Authorization': 'Bearer ' + key, 'Content-Type': 'application/json'})
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'Preflight failed: HTTP {error.code} at {url}') from None


def main():
    embedding_key = getpass.getpass('DashScope embedding key: ')
    judge_key = getpass.getpass('DeepSeek generation and judge key: ')
    model = 'deepseek-flash'
    info = api('https://api.deepseek.com/v1/models', judge_key)
    if model not in {x['id'] for x in info['data']}:
        raise RuntimeError('deepseek-flash is not in the available models')
    api('https://api.deepseek.com/v1/chat/completions', judge_key, {
        'model': model, 'messages': [{'role': 'user', 'content': 'Reply OK.'}],
        'max_tokens': 16, 'thinking': {'type': 'disabled'}})
    embedding = api('https://dashscope.aliyuncs.com/api/v1/services/embeddings/text-embedding/text-embedding',
        embedding_key, {'model': 'text-embedding-v4', 'input': {'texts': ['connection check']},
                        'parameters': {'dimension': 1024, 'output_type': 'dense'}})
    if len(embedding['output']['embeddings'][0]['embedding']) != 1024:
        raise RuntimeError('Embedding dimension mismatch')
    print('Preflight OK: deepseek-flash and text-embedding-v4 (1024).', flush=True)
    with socket.socket() as sock:
        if sock.connect_ex(('127.0.0.1', 8081)) == 0:
            raise RuntimeError('Port 8081 is busy')
    env = dict(os.environ)
    env.update(SERVER_PORT='8081', AGICORE_EMBEDDING_PROVIDER='dashscope',
        AGICORE_EMBEDDING_BASE_URL='https://dashscope.aliyuncs.com/api/v1',
        AGICORE_EMBEDDING_API_KEY=embedding_key, AGICORE_EMBEDDING_MODEL='text-embedding-v4',
        AGICORE_EMBEDDING_DIMENSION='1024', AGICORE_RERANK_ENABLED='true',
        AGICORE_RERANK_BASE_URL='http://127.0.0.1:8000', AGICORE_RERANK_MODEL='BAAI/bge-reranker-base',
        AGICORE_RAG_GENERATION_PROVIDER='DeepSeek', AGICORE_RAG_GENERATION_MODEL=model,
        AGICORE_RAG_GENERATION_BASE_URL='https://api.deepseek.com', AGICORE_RAG_CHAT_API_KEY=judge_key,
        AGICORE_OCR_ENABLED='false', RAGAS_JUDGE_API_KEY=judge_key, RAGAS_EMBEDDING_API_KEY=embedding_key,
        PYTHONIOENCODING='utf-8', PYTHONUNBUFFERED='1')
    output = ROOT / 'rag工作记录与脚本/评测产物与工作目录/wd/ragas-v4-full50-2026-09-27.json'
    if output.exists() or output.with_suffix('.responses.jsonl').exists():
        raise RuntimeError('Fresh output already exists; inspect before resuming')
    with (ROOT / 'tmp/ragas-v4-backend.log').open('w', encoding='utf8') as log:
        java = r'C:\Program Files\Java\jdk-24\bin\java.exe' if os.name == 'nt' else 'java'
        proc = subprocess.Popen([java, '-jar', 'backend/target/agicore-backend-0.1.0.jar'],
            cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        try:
            for _ in range(90):
                if proc.poll() is not None:
                    raise RuntimeError('Evaluation backend exited; inspect tmp/ragas-v4-backend.log')
                with socket.socket() as sock:
                    if sock.connect_ex(('127.0.0.1', 8081)) == 0:
                        break
                time.sleep(1)
            else:
                raise RuntimeError('Evaluation backend startup timed out')
            print(f'Evaluation backend ready, PID {proc.pid}, port 8081.', flush=True)
            cmd = [str(ROOT / 'ragas-eval/.venv/Scripts/python.exe'),
                   str(ROOT / 'rag工作记录与脚本/脚本/run_ragas_generation_eval.py'),
                   str(ROOT / 'rag工作记录与脚本/评测产物与工作目录/wd/retrieval-eval-50-generation-v4-2026-09-26.jsonl'),
                   '--base-url', 'http://localhost:8081', '--full-corpus', '--output', str(output)]
            result = subprocess.run(cmd, cwd=ROOT, env=env)
            return result.returncode
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()


if __name__ == '__main__':
    sys.exit(main())
