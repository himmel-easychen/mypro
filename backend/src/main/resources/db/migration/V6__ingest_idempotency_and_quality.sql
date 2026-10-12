create unique index if not exists uq_document_workspace_checksum
    on document(workspace_id, checksum)
    where checksum is not null;

create table if not exists source_unit_quality (
    id bigserial primary key,
    source_unit_id bigint not null references source_unit(id) on delete cascade,
    status varchar(32) not null,
    retry_count integer not null default 0,
    text_readability numeric(8, 6),
    table_row_consistency numeric(8, 6),
    row_boundary_confidence numeric(8, 6),
    noise_rate numeric(8, 6),
    coverage numeric(8, 6),
    failure_reason text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique(source_unit_id)
);

create index if not exists idx_source_unit_quality_status
    on source_unit_quality(status, updated_at);
