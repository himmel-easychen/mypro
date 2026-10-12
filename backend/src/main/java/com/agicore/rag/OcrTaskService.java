package com.agicore.rag;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class OcrTaskService {
    private final JdbcTemplate jdbcTemplate;
    public OcrTaskService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }
    public UUID create(String sourceFile, String storagePath) {
        UUID taskId = UUID.randomUUID();
        jdbcTemplate.update(
                "insert into ocr_ingest_task(task_id,source_file,storage_path,status) values (?,?,?,'queued')",
                taskId, sourceFile, storagePath);
        return taskId;
    }
    public Map<String, Object> find(UUID taskId) {
        return jdbcTemplate.queryForMap("select task_id,source_file,status,document_id,attempts,total_pages,processed_pages,page_pass_rate,error_message,created_at,updated_at from ocr_ingest_task where task_id=?", taskId);
    }

    public Map<String, Object> load(UUID taskId) {
        return jdbcTemplate.queryForMap("select task_id,source_file,storage_path,status,attempts from ocr_ingest_task where task_id=?", taskId);
    }

    public void markProcessing(UUID taskId) {
        jdbcTemplate.update("update ocr_ingest_task set status='processing',attempts=attempts+1,updated_at=now() where task_id=?", taskId);
    }

    public void markCompleted(UUID taskId, String status, UUID documentId,
                              int totalPages, int processedPages, double pagePassRate) {
        jdbcTemplate.update(
                "update ocr_ingest_task set status=?,document_id=?,total_pages=?,processed_pages=?,page_pass_rate=?,updated_at=now() where task_id=?",
                status, documentId, totalPages, processedPages, pagePassRate, taskId);
    }

    public void markFailed(UUID taskId, String message) {
        jdbcTemplate.update("update ocr_ingest_task set status='failed',error_message=?,updated_at=now() where task_id=?", message, taskId);
    }
}
