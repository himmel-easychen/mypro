package com.workpro.api;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import com.workpro.rag.OcrTaskService;
import com.workpro.rag.RedisOcrQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rag")
public class AsyncRagController {
    private final OcrTaskService tasks;
    private final RedisOcrQueue queue;
    private final Path storageDir;

    public AsyncRagController(OcrTaskService tasks, RedisOcrQueue queue,
                              @Value("${workpro.ocr.task-storage-dir:${java.io.tmpdir}/workpro-ocr-tasks}") String storageDir) {
        this.tasks = tasks;
        this.queue = queue;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    @PostMapping("/ingest/async")
    public ResponseEntity<Map<String, Object>> submit(@RequestParam("file") MultipartFile file) throws Exception {
        Files.createDirectories(storageDir);
        String name = file.getOriginalFilename() == null ? "upload.bin" : file.getOriginalFilename();
        Path target = storageDir.resolve(UUID.randomUUID() + "-" + Path.of(name).getFileName());
        Files.write(target, file.getBytes());
        UUID taskId = tasks.create(name, target.toString());
        queue.publish(taskId);
        return ResponseEntity.accepted().body(Map.of("task_id", taskId, "status", "queued"));
    }

    @GetMapping("/ingest/tasks/{taskId}")
    public Map<String, Object> status(@PathVariable UUID taskId) {
        return tasks.find(taskId);
    }

    @GetMapping(value = "/ingest/tasks/{taskId}/events", produces = "text/event-stream")
    public SseEmitter events(@PathVariable UUID taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        Thread worker = new Thread(() -> {
            try {
                for (int i = 0; i < 300; i++) {
                    Map<String, Object> state = tasks.find(taskId);
                    emitter.send(SseEmitter.event().name("ingest-status").data(state));
                    String status = String.valueOf(state.get("status"));
                    if ("ready".equals(status) || "needs_review".equals(status) || "failed".equals(status)) {
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(1000L);
                }
                emitter.complete();
            } catch (Exception exception) {
                emitter.completeWithError(exception);
            }
        });
        worker.setDaemon(true);
        worker.start();
        return emitter;
    }
}
