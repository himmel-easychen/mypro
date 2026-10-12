create table if not exists tender_task (
    id bigserial primary key,
    task_id varchar(64) not null unique,
    name text not null,
    owner_id varchar(128),
    status varchar(64) not null,
    current_mode varchar(64) not null,
    current_stage varchar(128),
    source_document_ids jsonb not null default '[]'::jsonb,
    metadata jsonb not null default '{}'::jsonb,
    created_by varchar(128) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    archived_at timestamptz
);
create index if not exists idx_tender_task_status on tender_task(status);
create index if not exists idx_tender_task_mode on tender_task(current_mode);
create index if not exists idx_tender_task_created_at on tender_task(created_at);

create table if not exists tender_artifact (
    id bigserial primary key,
    artifact_id varchar(64) not null unique,
    task_id varchar(64) not null,
    artifact_type varchar(64) not null,
    latest_version int not null,
    status varchar(64) not null,
    mode varchar(64) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);
create index if not exists idx_tender_artifact_task_type on tender_artifact(task_id, artifact_type);
create index if not exists idx_tender_artifact_task_status on tender_artifact(task_id, status);

create table if not exists tender_artifact_version (
    id bigserial primary key,
    artifact_id varchar(64) not null,
    task_id varchar(64) not null,
    version int not null,
    status varchar(64) not null,
    content jsonb not null,
    source_refs jsonb not null default '[]'::jsonb,
    evidence_refs jsonb not null default '[]'::jsonb,
    confirmation_refs jsonb not null default '[]'::jsonb,
    issues jsonb not null default '[]'::jsonb,
    metadata jsonb not null default '{}'::jsonb,
    created_by varchar(128) not null,
    created_at timestamptz not null default now(),
    unique(artifact_id, version)
);
create index if not exists idx_tender_artifact_version_task on tender_artifact_version(task_id);

create table if not exists tender_transition_log (
    id bigserial primary key,
    transition_id varchar(64) not null unique,
    task_id varchar(64) not null,
    from_status varchar(64) not null,
    to_status varchar(64) not null,
    from_mode varchar(64),
    to_mode varchar(64),
    triggered_by varchar(128) not null,
    trigger_type varchar(64) not null,
    reason text,
    related_artifact_ids jsonb not null default '[]'::jsonb,
    related_issue_ids jsonb not null default '[]'::jsonb,
    metadata jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now()
);
create index if not exists idx_tender_transition_task on tender_transition_log(task_id, created_at);

create table if not exists tender_confirmation_request (
    id bigserial primary key,
    confirmation_id varchar(64) not null unique,
    task_id varchar(64) not null,
    status varchar(64) not null,
    question text not null,
    reason text not null,
    options jsonb not null default '[]'::jsonb,
    selected_option jsonb,
    custom_answer text,
    affected_artifact_ids jsonb not null default '[]'::jsonb,
    affected_sections jsonb not null default '[]'::jsonb,
    created_by varchar(128) not null,
    answered_by varchar(128),
    created_at timestamptz not null default now(),
    answered_at timestamptz
);
create index if not exists idx_tender_confirmation_task_status on tender_confirmation_request(task_id, status);

create table if not exists tender_review_issue (
    id bigserial primary key,
    issue_id varchar(64) not null unique,
    task_id varchar(64) not null,
    artifact_id varchar(64),
    artifact_version int,
    issue_type varchar(64) not null,
    severity varchar(64) not null,
    status varchar(64) not null,
    location text,
    description text not null,
    suggested_fix text,
    blocked boolean not null,
    rollback_target varchar(64),
    created_by varchar(128) not null,
    created_at timestamptz not null default now(),
    resolved_at timestamptz
);
create index if not exists idx_tender_review_issue_task_status on tender_review_issue(task_id, status);
create index if not exists idx_tender_review_issue_task_blocked on tender_review_issue(task_id, blocked);
