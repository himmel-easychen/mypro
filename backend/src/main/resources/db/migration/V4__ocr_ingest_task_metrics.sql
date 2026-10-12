alter table ocr_ingest_task add column if not exists total_pages integer;
alter table ocr_ingest_task add column if not exists processed_pages integer;
alter table ocr_ingest_task add column if not exists page_pass_rate numeric(8, 6);
