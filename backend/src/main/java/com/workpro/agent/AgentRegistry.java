package com.workpro.agent;
import org.springframework.stereotype.Service; import java.util.*; import static com.workpro.agent.AgentContracts.*;
@Service public class AgentRegistry { private final Map<String,Agent> agents; public AgentRegistry(List<Agent> list){agents=list.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(a->a.definition().agentId(),a->a));} public Agent get(String id){var a=agents.get(id);if(a==null)throw new IllegalArgumentException("AGENT_NOT_FOUND: "+id);return a;} public Set<String> ids(){return agents.keySet();} }
