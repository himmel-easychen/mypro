package com.workpro.runtime;

import com.workpro.agent.AgentExecutor;
import com.workpro.agent.AgentContracts.*;
import com.workpro.dialogue.DocumentExportService;
import com.workpro.domain.Contracts.ArtifactPath;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;

@Service
public class AgentGraphService {
    private final AgentExecutor executor;
    private final ExecutionPersistence persistence;
    private final DocumentExportService exporter;
    public AgentGraphService(AgentExecutor executor, ExecutionPersistence persistence, DocumentExportService exporter) { this.executor = executor; this.persistence = persistence; this.exporter = exporter; }

    public AgentGraphResult execute(String taskId, String userId, String query, boolean research, boolean document) {
        var run = persistence.startTaskRun(taskId, query, research ? "research" : "chat");
        var agentContext = new AgentContext(taskId, run.runId().toString(), userId, null, query,
                Map.of("evidence", List.of()));
        agentContext.put("taskRunPk", run.taskRunPk());
        var graphContext = new GraphContext(taskId, "dialogue", query);
        graphContext.put("agentContext", agentContext);
        var registry = new NodeExecutorRegistry();
        registry.register("research", (node, context) -> run("research", context));
        registry.register("writer", (node, context) -> run("writer", context));
        registry.register("review", (node, context) -> run("review", context));
        var nodes = new ArrayList<NodeDefinition>();
        if (research) nodes.add(new NodeDefinition("research", "AGENT", "research", 1, Duration.ofSeconds(30)));
        nodes.add(new NodeDefinition("writer", "AGENT", "writer", 1, Duration.ofSeconds(60)));
        nodes.add(new NodeDefinition("review", "AGENT", "review", 0, Duration.ofSeconds(15)));
        var edges = new ArrayList<EdgeDefinition>();
        if (research) edges.add(new EdgeDefinition("research", "writer", "success"));
        edges.add(new EdgeDefinition("writer", "review", "success"));
        TaskExecutionResult execution = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            execution = new StaticGraphRuntime(registry).execute(new GraphDefinition("dialogue", "1", nodes, edges), graphContext);
            if (execution.status() != TaskRunStatus.FAILED || !"review".equals(execution.currentNode())) break;
            agentContext.put("review_issues", execution.message());
        }
        persistence.finishTaskRun(run.taskRunPk(), execution);
        List<ArtifactPath> artifacts = List.of();
        if (document && execution.status() == TaskRunStatus.SUCCEEDED) try { artifacts = List.of(exporter.exportDocx(taskId, agentContext.get("answer"))); } catch (Exception e) { execution = new TaskExecutionResult(TaskRunStatus.FAILED, "export", e.getMessage(), execution.snapshots()); }
        return new AgentGraphResult(execution, agentContext, artifacts);
    }

    private NodeResult run(String id, GraphContext graphContext) {
        var context = graphContext.get("agentContext", AgentContext.class);
        long nodePk = persistence.startNodeRun(context.get("taskRunPk"), id, "AGENT");
        long started = System.nanoTime();
        long agentPk = persistence.startAgentRun(nodePk, id, "1", 1);
        var result = executor.execute(id, new AgentInput(id, Map.of()), context);
        NodeResult nodeResult;
        if (result instanceof Success s) nodeResult = NodeResult.success(s.output().message());
        else if (result instanceof Blocked b) nodeResult = NodeResult.failure("E0001", b.reason());
        else { var f = (Failure) result; nodeResult = NodeResult.failure(f.error().code(), f.error().message()); }
        persistence.finishNodeRun(nodePk, nodeResult, Duration.ofNanos(System.nanoTime()-started).toMillis());
        persistence.finishAgentRun(agentPk, nodeResult.status(), nodeResult.message(), nodeResult.errorCode(), Duration.ofNanos(System.nanoTime()-started).toMillis());
        return nodeResult;
    }

    public record AgentGraphResult(TaskExecutionResult execution, AgentContext context, List<ArtifactPath> artifacts) { }
}
