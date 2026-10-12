package com.workpro.runtime;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class GraphRuntimeTest {
    @Test
    void executesNodesInDependencyOrder() {
        var order = new java.util.ArrayList<String>();
        var graph = new GraphDefinition("g", "1", List.of(
                new NodeDefinition("a", "AGENT", "a", 0, Duration.ofSeconds(1)),
                new NodeDefinition("b", "AGENT", "b", 0, Duration.ofSeconds(1))),
                List.of(new EdgeDefinition("a", "b", null)));
        var registry = new NodeExecutorRegistry();
        registry.register("a", (n, c) -> { order.add("a"); return NodeResult.success("ok"); });
        registry.register("b", (n, c) -> { order.add("b"); return NodeResult.success("ok"); });
        var result = new StaticGraphRuntime(registry).execute(graph, new GraphContext("t", "g", "q"));
        assertEquals(List.of("a", "b"), order);
        assertEquals(TaskRunStatus.SUCCEEDED, result.status());
    }

    @Test
    void retriesFailedNodeThenSucceeds() {
        var attempts = new AtomicInteger();
        var graph = new GraphDefinition("g", "1", List.of(
                new NodeDefinition("a", "AGENT", "a", 2, Duration.ofSeconds(1))), List.of());
        var registry = new NodeExecutorRegistry();
        registry.register("a", (n, c) -> attempts.incrementAndGet() < 3
                ? NodeResult.failure("E1", "temporary") : NodeResult.success("ok"));
        var result = new StaticGraphRuntime(registry).execute(graph, new GraphContext("t", "g", "q"));
        assertEquals(3, attempts.get());
        assertEquals(TaskRunStatus.SUCCEEDED, result.status());
    }

    @Test
    void returnsCancelledBeforeStartingNextNode() {
        var context = new GraphContext("t-cancel", "g", "q");
        context.cancel();
        var graph = new GraphDefinition("g", "1", List.of(new NodeDefinition("a", "AGENT", "a", 0, Duration.ofSeconds(1))), List.of());
        var registry = new NodeExecutorRegistry();
        registry.register("a", (n, c) -> { fail("cancelled task must not execute"); return NodeResult.success("x"); });
        assertEquals(TaskRunStatus.CANCELLED, new StaticGraphRuntime(registry).execute(graph, context).status());
    }
}
