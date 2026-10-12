package com.workpro.runtime;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
public class ExecutionPersistence {
    private static final UUID DEFAULT_WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcTemplate jdbc;
    public ExecutionPersistence(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public RunStart startTaskRun(String taskId, String query, String route) {
        UUID id = parseId(taskId); Instant now = Instant.now();
        jdbc.update("insert into workspace(workspace_id,name,code) values (?,?,?) on conflict(code) do nothing", DEFAULT_WORKSPACE, "Default", "default");
        jdbc.update("insert into task(task_id,workspace_id,title,query,route,status) values (?,(select id from workspace where code='default'),?,?,?,'running') on conflict(task_id) do update set query=excluded.query,route=excluded.route,status='running',updated_at=now()", id, query, query, route);
        Long taskPk = jdbc.queryForObject("select id from task where task_id=?", Long.class, id);
        Integer attempt = jdbc.queryForObject("select coalesce(max(attempt),0)+1 from task_run where task_id=?", Integer.class, taskPk);
        UUID runId = UUID.randomUUID();
        long pk = jdbc.queryForObject("insert into task_run(run_id,task_id,attempt,status,route,started_at) values (?,?,?,'running',?,?) returning id", Long.class, runId, taskPk, attempt, route, Timestamp.from(now));
        jdbc.update("update task set latest_run_id=?,updated_at=now() where id=?", pk, taskPk);
        return new RunStart(pk, runId);
    }
    public long startNodeRun(long taskRunPk, String name, String type) {
        return jdbc.queryForObject(
                "insert into node_run(node_run_id,task_run_id,node_name,node_type,attempt,status,started_at) " +
                        "values (?,?,?,?," +
                        "(select coalesce(max(attempt),0)+1 from node_run where task_run_id=? and node_name=?)," +
                        "'running',?) returning id",
                Long.class,
                UUID.randomUUID(),
                taskRunPk,
                name,
                type,
                taskRunPk,
                name,
                Timestamp.from(Instant.now()));
    }
    public void finishNodeRun(long id, NodeResult result, long duration) { jdbc.update("update node_run set status=?,error_code=?,error_message=?,output_summary=?,duration_ms=?,finished_at=now(),updated_at=now() where id=?", result.status(), result.errorCode(), result.message(), result.message(), duration, id); }
    public long startAgentRun(long nodePk, String agentId, String version, int attempt) { return jdbc.queryForObject("insert into agent_run(agent_run_id,node_run_id,agent_id,agent_version,attempt,status) values (?,?,?,?,?,'running') returning id", Long.class, UUID.randomUUID(), nodePk, agentId, version, attempt); }
    public void finishAgentRun(long id, String status, String message, String code, long duration) { jdbc.update("update agent_run set status=?,error_code=?,error_message=?,output_summary=?,duration_ms=?,finished_at=now(),updated_at=now() where id=?", status, code, message, message, duration, id); }
    public void finishTaskRun(long id, TaskExecutionResult result) { jdbc.update("update task_run set status=?,current_node=?,error_message=?,finished_at=now(),updated_at=now() where id=?", result.status().name(), result.currentNode(), result.message(), id); }
    private UUID parseId(String value) { try { return UUID.fromString(value); } catch (Exception e) { return UUID.nameUUIDFromBytes(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); } }
    public record RunStart(long taskRunPk, UUID runId) { }
}
