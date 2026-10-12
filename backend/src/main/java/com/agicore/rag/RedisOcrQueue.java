package com.agicore.rag;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class RedisOcrQueue {
    private final StringRedisTemplate redis;
    private final OcrTaskService tasks;
    private final RagServices ragServices;
    private final String stream;
    private final String group;
    private final String consumer;

    public RedisOcrQueue(StringRedisTemplate redis, OcrTaskService tasks, RagServices ragServices,
                         @Value("${agicore.ocr.redis.stream:ocr-ingest}") String stream,
                         @Value("${agicore.ocr.redis.group:ocr-workers}") String group,
                         @Value("${agicore.ocr.redis.consumer:worker-1}") String consumer) {
        this.redis = redis;
        this.tasks = tasks;
        this.ragServices = ragServices;
        this.stream = stream;
        this.group = group;
        this.consumer = consumer;
    }

    public void publish(UUID taskId) {
        redis.opsForStream().add(StreamRecords.newRecord().ofMap(Map.of("task_id", taskId.toString())).withStreamKey(stream));
    }

    @Scheduled(fixedDelayString = "${agicore.ocr.redis.poll-delay-ms:1000}")
    public void consume() {
        ensureGroup();
        var records = redis.opsForStream().read(Consumer.from(group, consumer),
                StreamReadOptions.empty().count(1).block(Duration.ofMillis(500)),
                StreamOffset.create(stream, ReadOffset.lastConsumed()));
        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            String value = String.valueOf(record.getValue().get("task_id"));
            UUID taskId = UUID.fromString(value);
            try {
                tasks.markProcessing(taskId);
                Map<String, Object> task = tasks.load(taskId);
                byte[] bytes = Files.readAllBytes(Path.of(String.valueOf(task.get("storage_path"))));
                MultipartFile file = new StoredMultipartFile(String.valueOf(task.get("source_file")), bytes);
                Map<String, Object> result = ragServices.ingest(file);
                UUID documentId = UUID.fromString(String.valueOf(result.get("doc_id")));
                if (!Boolean.TRUE.equals(result.get("deduplicated"))) {
                    ragServices.persist(documentId.toString());
                }
                int totalPages = ((Number) result.getOrDefault("total_pages", 0)).intValue();
                int processedPages = ((Number) result.getOrDefault("processed_pages", 0)).intValue();
                double pagePassRate = ((Number) result.getOrDefault("page_pass_rate", 1.0)).doubleValue();
                tasks.markCompleted(taskId, String.valueOf(result.getOrDefault("status", "completed")),
                        documentId, totalPages, processedPages, pagePassRate);
            } catch (Exception ex) {
                tasks.markFailed(taskId, ex.getMessage());
            } finally {
                redis.opsForStream().acknowledge(stream, group, record.getId());
            }
        }
    }

    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(stream, ReadOffset.latest(), group);
        } catch (Exception ignored) {
        }
    }

    private record StoredMultipartFile(String name, byte[] content) implements MultipartFile {
        public String getName() { return "file"; }
        public String getOriginalFilename() { return name; }
        public String getContentType() { return contentTypeFor(name); }
        public boolean isEmpty() { return content.length == 0; }
        public long getSize() { return content.length; }
        public byte[] getBytes() { return content; }
        public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(content); }
        public void transferTo(java.io.File dest) throws java.io.IOException { Files.write(dest.toPath(), content); }
    }

    static String contentTypeFor(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (name.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (name.endsWith(".xls")) return "application/vnd.ms-excel";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}
