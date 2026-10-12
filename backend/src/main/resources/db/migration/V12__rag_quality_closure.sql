create table if not exists rag_answer_trace (
  id bigint generated always as identity primary key,
  trace_id uuid not null unique,
  query text not null,
  document_id uuid,
  chapter_id varchar(128),
  history_json jsonb not null default '[]',
  disable_query_transform boolean not null default false,
  status varchar(32) not null,
  error_code varchar(64),
  answer text,
  refusal boolean not null default false,
  refusal_reason varchar(64),
  citations_json jsonb not null default '[]',
  retrieved_chunk_ids_json jsonb not null default '[]',
  evidence_json jsonb not null default '[]',
  retrieval_json jsonb not null default '{}',
  prompt text,
  raw_response text,
  latency_ms bigint,
  model_latency_ms bigint,
  validation_json jsonb not null default '{}',
  created_at timestamptz not null default now()
);

create table if not exists rag_answer_feedback (
  id bigint generated always as identity primary key,
  feedback_id uuid not null unique,
  trace_id uuid not null references rag_answer_trace(trace_id) on delete cascade,
  user_id varchar(128),
  rating varchar(32) not null,
  category varchar(64),
  comment text,
  metadata_json jsonb not null default '{}',
  created_at timestamptz not null default now()
);

create table if not exists rag_answer_audit_review (
  id bigint generated always as identity primary key,
  review_id uuid not null unique,
  trace_id uuid not null references rag_answer_trace(trace_id) on delete cascade,
  reviewer varchar(128),
  required_facts_covered boolean,
  numeric_unit_correct boolean,
  citation_supported boolean,
  should_refuse boolean,
  error_type varchar(128),
  notes text,
  metadata_json jsonb not null default '{}',
  created_at timestamptz not null default now()
);

create index if not exists idx_rag_answer_trace_created_at on rag_answer_trace(created_at desc);
create index if not exists idx_rag_answer_feedback_trace_id on rag_answer_feedback(trace_id);
create index if not exists idx_rag_answer_audit_trace_id on rag_answer_audit_review(trace_id);
