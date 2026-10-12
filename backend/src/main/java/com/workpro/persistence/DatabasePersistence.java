package com.workpro.persistence;

import com.workpro.domain.Contracts.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
public class DatabasePersistence {
    private static final UUID DEFAULT_WORKSPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcTemplate jdbc;
    public DatabasePersistence(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void recordChat(ChatRequest request, AggregatedTaskResult result) {
        UUID workspaceId = DEFAULT_WORKSPACE;
        UUID userId = stableUuid(request.user_id(), "user");
        UUID taskId = stableUuid(request.task_id(), "task");
        UUID runId = UUID.randomUUID();
        jdbc.update("insert into workspace(workspace_id,name,code) values(?,?,?) on conflict(code) do nothing", workspaceId, "Default", "default");
        Long ws = one("select id from workspace where code = ?", "default");
        String username = request.user_id() == null ? "anonymous" : request.user_id();
        jdbc.update("insert into app_user(user_id,workspace_id,username) values(?,?,?) on conflict(user_id) do nothing", userId, ws, username);
        Long usr = one("select id from app_user where user_id = ?", userId);
        jdbc.update("insert into task(task_id,workspace_id,user_id,title,query,route,status) values(?,?,?,?,?,?,?) on conflict(task_id) do update set status=excluded.status,updated_at=now()", taskId, ws, usr, request.query(), request.query(), result.current_node(), result.status().name().toLowerCase());
        Long task = one("select id from task where task_id = ?", taskId);
        Long existingRun = one("select latest_run_id from task where id = ?", task);
        if (existingRun != null) return;
        jdbc.update("insert into task_run(run_id,task_id,attempt,status,current_node,route,started_at,finished_at) values(?,?,?,?,?,?,now(),now())", runId, task, 1, result.status().name().toLowerCase(), result.current_node(), result.current_node());
        Long run = one("select id from task_run where run_id = ?", runId);
        jdbc.update("update task set latest_run_id=?,updated_at=now() where id=?", run, task);
        jdbc.update("insert into conversation_turn(turn_id,task_id,task_run_id,workspace_id,user_id,role,content) values(?,?,?,?,?,?,?)", UUID.randomUUID(), task, run, ws, usr, "user", request.query() == null ? "" : request.query());
        if (result.node_runs() != null && !result.node_runs().isEmpty()) for (NodeRunSummary n : result.node_runs())
            jdbc.update("insert into node_run(node_run_id,task_run_id,node_name,node_type,attempt,status,error_code,output_summary,duration_ms) values(?,?,?,?,?,?,?,?,?)", UUID.randomUUID(), run, n.node_name(), "agent", 1, n.status(), n.error_code(), n.summary(), n.duration_ms());
        else jdbc.update("insert into node_run(node_run_id,task_run_id,node_name,node_type,attempt,status,output_summary) values(?,?,?,?,?,?,?)", UUID.randomUUID(), run, result.current_node(), "agent", 1, result.status().name().toLowerCase(), result.message());
        if (result.artifact_paths() != null) for (ArtifactPath a : result.artifact_paths()) {
            jdbc.update("insert into artifact(artifact_id,task_id,task_run_id,artifact_type,file_name,storage_uri,checksum) values(?,?,?,?,?,?,?) on conflict(task_id,storage_uri) do nothing",
                    parseUuid(a.artifact_id()), task, run, a.type(), a.path(), a.path(), a.checksum());
        }
        if (result.evidence_refs() != null) for (EvidenceRef e : result.evidence_refs()) {
            Long chunk = one("select id from document_chunk where chunk_id = ?", parseUuidOrNull(e.hash_or_id()));
            jdbc.update("insert into evidence_ref(evidence_id,task_run_id,chunk_id,source_file,location,hash_or_id,confidence) values(?,?,?,?,?,?,?) on conflict(task_run_id,hash_or_id) do nothing",
                    UUID.randomUUID(), run, chunk, e.source_file(), e.location(), e.hash_or_id(), e.confidence());
        }
    }

    private Long one(String sql, Object value) { return jdbc.query(sql, ps -> ps.setObject(1, value), rs -> rs.next() ? rs.getLong(1) : null); }
    private UUID parseUuid(String value) { try { return UUID.fromString(value); } catch (Exception e) { return UUID.randomUUID(); } }
    private UUID parseUuidOrNull(String value) { try { return UUID.fromString(value); } catch (Exception e) { return null; } }
    private UUID stableUuid(String value, String prefix) { return UUID.nameUUIDFromBytes((prefix + ":" + (value == null ? "anonymous" : value)).getBytes(StandardCharsets.UTF_8)); }
}
