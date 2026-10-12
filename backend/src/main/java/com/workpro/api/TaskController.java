package com.workpro.api;

import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskQueryService service;
    public TaskController(TaskQueryService service) { this.service = service; }

    @GetMapping("/{taskId}")
    public Map<String, Object> get(@PathVariable String taskId) { return service.getTask(taskId); }

    @GetMapping
    public List<Map<String, Object>> list(@RequestParam(defaultValue = "20") int limit) { return service.listTasks(limit); }
}
