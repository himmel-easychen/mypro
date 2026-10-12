-- P0/P1 consistency indexes. All statements are idempotent so existing
-- installations can be upgraded without rewriting OCR data.
create index if not exists idx_document_chunk_parent_role
    on document_chunk(parent_chunk_id, chunk_role);

create index if not exists idx_chunk_embedding_ready_native
    on chunk_embedding(collection_id, status)
    where embedding is not null;

-- These columns are present in V5, but keep the migration safe for databases
-- created from an older baseline or restored from a partial dump.
alter table document_block add column if not exists bbox_json jsonb;
alter table document_block add column if not exists confidence numeric(6,5);
alter table document_block add column if not exists metadata_json jsonb not null default '{}'::jsonb;
