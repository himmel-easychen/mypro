create table if not exists ocr_ingest_task (
    id bigint generated always as identity primary key,
    task_id uuid not null unique,
    source_file varchar(512) not null,
    storage_path varchar(1024) not null,
    status varchar(32) not null default 'queued',
    document_id uuid,
    attempts integer not null default 0,
    error_message text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists idx_ocr_ingest_task_status on ocr_ingest_task(status, created_at);
