create table if not exists source_unit (
    id bigserial primary key,
    source_unit_id uuid not null unique,
    document_id bigint not null references document(id) on delete cascade,
    unit_type varchar(32) not null,
    page_number integer,
    logical_key varchar(256),
    sheet_name varchar(256),
    raw_text text,
    normalized_text text,
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);

create index if not exists idx_source_unit_document
    on source_unit(document_id, page_number);

create table if not exists document_block (
    id bigserial primary key,
    block_id uuid not null unique,
    document_id bigint not null references document(id) on delete cascade,
    source_unit_id bigint not null references source_unit(id) on delete cascade,
    block_type varchar(32) not null,
    order_index integer not null,
    raw_text text,
    normalized_text text,
    bbox_json jsonb,
    confidence numeric(6,5),
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique(source_unit_id, order_index)
);

create index if not exists idx_document_block_document
    on document_block(document_id, source_unit_id, order_index);

create table if not exists document_table (
    id bigserial primary key,
    table_id uuid not null unique,
    document_id bigint not null references document(id) on delete cascade,
    source_unit_id bigint not null references source_unit(id) on delete cascade,
    table_index integer not null,
    sheet_name varchar(256),
    headers_json jsonb not null default '[]'::jsonb,
    structure_status varchar(32) not null default 'parsed',
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique(source_unit_id, table_index)
);

create table if not exists document_table_cell (
    id bigserial primary key,
    table_id bigint not null references document_table(id) on delete cascade,
    row_index integer not null,
    column_index integer not null,
    column_name varchar(256),
    raw_text text,
    normalized_text text,
    unit varchar(64),
    metadata_json jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    unique(table_id, row_index, column_index)
);

create index if not exists idx_document_table_cell_lookup
    on document_table_cell(table_id, row_index, column_index);
