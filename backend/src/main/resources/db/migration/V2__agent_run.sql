create table if not exists agent_run (
  id bigint generated always as identity primary key,
  agent_run_id uuid not null unique,
  node_run_id bigint not null references node_run(id) on delete cascade,
  agent_id varchar(128) not null,
  agent_version varchar(64),
  attempt integer not null default 1,
  status varchar(32) not null,
  input_summary text,
  output_summary text,
  error_code varchar(64),
  error_message text,
  started_at timestamptz not null default now(),
  finished_at timestamptz,
  duration_ms bigint,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);
create index if not exists idx_agent_run_node on agent_run(node_run_id, created_at);
create index if not exists idx_agent_run_agent on agent_run(agent_id, created_at desc);
