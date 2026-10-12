create table if not exists document_failed_page (
    id bigserial primary key,
    document_id bigint not null references document(id) on delete cascade,
    page_number integer not null,
    status varchar(32) not null default 'failed',
    retry_count integer not null default 1,
    failure_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(document_id, page_number)
);

create index if not exists idx_document_failed_page_status
    on document_failed_page(status, updated_at);
