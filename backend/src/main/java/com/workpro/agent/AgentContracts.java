package com.workpro.agent;

import java.time.Duration;
import java.util.*;

public final class AgentContracts {
    private AgentContracts() {}
    public record AgentDefinition(String agentId, String version, Set<String> capabilities, Set<String> toolIds, Duration timeout, int maxRetries) {
        public AgentDefinition { capabilities=Set.copyOf(capabilities==null?Set.of():capabilities); toolIds=Set.copyOf(toolIds==null?Set.of():toolIds); timeout=timeout==null?Duration.ofSeconds(30):timeout; }
    }
    public record AgentInput(String operation, Map<String,Object> parameters) { public AgentInput { parameters=Map.copyOf(parameters==null?Map.of():parameters); } }
    public record AgentContext(String taskId,String runId,String userId,String workspaceId,String query,Map<String,Object> values) {
        public AgentContext { values=new HashMap<>(values==null?Map.of():values); }
        public void put(String key,Object value){values.put(key,value);} @SuppressWarnings("unchecked") public <T>T get(String key){return (T)values.get(key);}
    }
    public record AgentOutput(String status,String message,Map<String,Object> data){ public AgentOutput{data=Map.copyOf(data==null?Map.of():data);} }
    public record AgentError(String code,String message,boolean retryable){ }
    public sealed interface AgentResult permits Success,Failure,Blocked { }
    public record Success(AgentOutput output) implements AgentResult { }
    public record Failure(AgentError error) implements AgentResult { }
    public record Blocked(String reason) implements AgentResult { }
    public interface Agent { AgentDefinition definition(); AgentResult execute(AgentInput input,AgentContext context); }
}
