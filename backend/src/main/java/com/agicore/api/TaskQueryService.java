package com.agicore.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class TaskQueryService {
    private final JdbcTemplate jdbc;
    public TaskQueryService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public Map<String, Object> getTask(String taskId) {
        UUID id = parseId(taskId);
        List<Map<String, Object>> tasks = jdbc.queryForList("select task_id,title,query,route,status,created_at,updated_at from task where task_id=?", id);
        if (tasks.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "task not found");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("task", tasks.get(0));
        result.put("latest_run", jdbc.queryForList("select tr.run_id,tr.attempt,tr.status,tr.current_node,tr.route,tr.started_at,tr.finished_at,tr.error_message from task_run tr join task t on t.latest_run_id=tr.id where t.task_id=?", id));
        result.put("node_runs", jdbc.queryForList("select nr.node_run_id,nr.node_name,nr.node_type,nr.attempt,nr.status,nr.error_code,nr.error_message,nr.output_summary,nr.duration_ms,nr.started_at,nr.finished_at from node_run nr join task_run tr on tr.id=nr.task_run_id join task t on t.latest_run_id=tr.id where t.task_id=? order by nr.id", id));
        result.put("artifacts", jdbc.queryForList("select artifact_id,artifact_type,file_name,storage_uri,checksum,created_at from artifact where task_id=(select id from task where task_id=?) order by id", id));
        return result;
    }

    public List<Map<String, Object>> listTasks(int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        return jdbc.queryForList("select task_id,title,query,route,status,created_at,updated_at from task order by updated_at desc limit ?", size);
    }

    private UUID parseId(String value) {
        try { return UUID.fromString(value); }
        catch (Exception e) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)); }
    }
}
