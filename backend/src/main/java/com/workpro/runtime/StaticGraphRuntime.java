package com.workpro.runtime;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

interface GraphRuntime { TaskExecutionResult execute(GraphDefinition graph, GraphContext context); }

record GraphDefinition(String graphId, String version, List<NodeDefinition> nodes, List<EdgeDefinition> edges) {
    GraphDefinition { nodes = List.copyOf(nodes); edges = List.copyOf(edges); }
}
record NodeDefinition(String nodeId, String nodeType, String executorKey, int maxRetries, Duration timeout) {}
record EdgeDefinition(String from, String to, String condition) {}
record GraphContext(String taskId, String graphId, String userQuery) {
    private static final Map<String, Object> VALUES = new ConcurrentHashMap<>();
    public void put(String key, Object value) { VALUES.put(taskId + ":" + key, value); }
    public <T> T get(String key, Class<T> type) { return type.cast(VALUES.get(taskId + ":" + key)); }
    public void cancel() { VALUES.put(taskId + ":cancelled", true); }
    public boolean cancelled() { return Boolean.TRUE.equals(VALUES.get(taskId + ":cancelled")); }
}
record NodeResult(String status, String errorCode, String message) {
    static NodeResult success(String message) { return new NodeResult("success", null, message); }
    static NodeResult failure(String code, String message) { return new NodeResult("failed", code, message); }
    boolean successful() { return "success".equals(status); }
}

@FunctionalInterface interface NodeExecutor { NodeResult execute(NodeDefinition node, GraphContext context) throws Exception; }

class NodeExecutorRegistry {
    private final Map<String, NodeExecutor> executors = new ConcurrentHashMap<>();
    void register(String key, NodeExecutor executor) { executors.put(key, executor); }
    NodeExecutor get(String key) { var e = executors.get(key); if (e == null) throw new IllegalArgumentException("未注册节点执行器: " + key); return e; }
}

public class StaticGraphRuntime implements GraphRuntime {
    public static StaticGraphRuntime facade() { return new StaticGraphRuntime(new NodeExecutorRegistry()); }
    private final NodeExecutorRegistry registry;
    public StaticGraphRuntime(NodeExecutorRegistry registry) { this.registry = registry; }
    public record Step(String id, int retries, Duration timeout, java.util.function.Supplier<String> action) {}
    public record FacadeResult(String status, String currentNode, String message, List<NodeSnapshot> snapshots) {}
    public FacadeResult execute(String taskId, String graphId, String query, List<Step> steps) {
        var nodes = steps.stream().map(s -> new NodeDefinition(s.id(), "AGENT", s.id(), s.retries(), s.timeout())).toList();
        var edges = new ArrayList<EdgeDefinition>();
        for (int i = 1; i < steps.size(); i++) edges.add(new EdgeDefinition(steps.get(i-1).id(), steps.get(i).id(), "success"));
        var local = new NodeExecutorRegistry(); steps.forEach(s -> local.register(s.id(), (n, c) -> NodeResult.success(s.action().get())));
        var result = new StaticGraphRuntime(local).execute(new GraphDefinition(graphId, "1", nodes, edges), new GraphContext(taskId, graphId, query));
        return new FacadeResult(result.status().name(), result.currentNode(), result.message(), result.snapshots());
    }
    @Override public TaskExecutionResult execute(GraphDefinition graph, GraphContext context) {
        var snapshots = new ArrayList<NodeSnapshot>(); var done = new HashMap<String, NodeResult>();
        int guard = graph.nodes().size() * 3 + 1;
        while (done.size() < graph.nodes().size() && guard-- > 0) {
            if (context.cancelled()) return new TaskExecutionResult(TaskRunStatus.CANCELLED, null, "任务已取消", snapshots);
            boolean progressed = false;
            for (var node : graph.nodes()) {
                if (done.containsKey(node.nodeId()) || !ready(node, graph.edges(), done)) continue;
                if (!edgeAllowed(node, graph.edges(), done)) { done.put(node.nodeId(), NodeResult.success("skipped")); snapshots.add(new NodeSnapshot(node.nodeId(), "skipped", null, "条件不满足", 0, 0)); progressed = true; continue; }
                progressed = true; long start = System.nanoTime(); int attempts = 0; NodeResult result = null;
                while (attempts++ <= node.maxRetries()) { try { result = run(node, context); if (result.successful()) break; } catch (Exception e) { result = NodeResult.failure("E0005", e.getMessage()); } }
                long duration = Duration.ofNanos(System.nanoTime() - start).toMillis(); snapshots.add(new NodeSnapshot(node.nodeId(), result.successful()?"success":"failed", result.errorCode(), result.message(), duration, attempts)); done.put(node.nodeId(), result);
                if (!result.successful()) return new TaskExecutionResult(TaskRunStatus.FAILED, node.nodeId(), result.message(), snapshots);
            }
            if (!progressed) return new TaskExecutionResult(TaskRunStatus.FAILED, null, "Graph 存在循环依赖或无法满足的条件", snapshots);
        }
        return new TaskExecutionResult(TaskRunStatus.SUCCEEDED, snapshots.isEmpty()?null:snapshots.get(snapshots.size()-1).nodeId(), "执行完成", snapshots);
    }
    private NodeResult run(NodeDefinition node, GraphContext context) throws Exception { var executor = Executors.newSingleThreadExecutor(); try { return executor.submit(() -> registry.get(node.executorKey()).execute(node, context)).get(node.timeout().toMillis(), TimeUnit.MILLISECONDS); } finally { executor.shutdownNow(); } }
    private boolean ready(NodeDefinition n, List<EdgeDefinition> edges, Map<String,NodeResult> done) { return edges.stream().filter(e -> e.to().equals(n.nodeId())).allMatch(e -> done.containsKey(e.from())); }
    private boolean edgeAllowed(NodeDefinition n, List<EdgeDefinition> edges, Map<String,NodeResult> done) { return edges.stream().filter(e -> e.to().equals(n.nodeId())).allMatch(e -> e.condition()==null || ("success".equals(e.condition()) && done.get(e.from()).successful()) || (e.condition().equals(done.get(e.from()).status()))); }
}
