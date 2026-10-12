alter table chunk_embedding
    add column if not exists embedding_json jsonb;

create index if not exists idx_chunk_embedding_ready
    on chunk_embedding(collection_id, status)
    where embedding_json is not null;
