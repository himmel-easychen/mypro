create extension if not exists vector;

alter table chunk_embedding
    add column if not exists embedding vector(1024);

create index if not exists idx_chunk_embedding_vector
    on chunk_embedding using hnsw (embedding vector_cosine_ops)
    where embedding is not null;
