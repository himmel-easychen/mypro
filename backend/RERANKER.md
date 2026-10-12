# Local reranker

The backend supports Hugging Face Text Embeddings Inference (TEI) using the
`/rerank` endpoint. The checked-in Compose file serves `BAAI/bge-reranker-base`
on port 8000 and keeps its model cache in a named Docker volume.

Start the service from `backend/`:

```powershell
docker compose -f reranker.compose.yml up -d
```

Enable it for the backend process with:

```powershell
$env:WORKPRO_RERANK_ENABLED = 'true'
$env:WORKPRO_RERANK_BASE_URL = 'http://localhost:8000'
$env:WORKPRO_RERANK_PATH = '/rerank'
```

The reranker is disabled by default so an unconfigured environment does not
make an external request for every search. When enabled, candidate batches are
limited to 32 texts per TEI request and failures keep the existing retrieval
ordering.
