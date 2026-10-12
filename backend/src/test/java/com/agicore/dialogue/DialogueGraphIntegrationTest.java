package com.agicore.dialogue;

import com.agicore.domain.Contracts.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.boot.test.context.SpringBootTest(properties = {
        "agicore.model.llm.provider=mock",
        "agicore.model.embedding.provider=mock",
        "agicore.model.embedding.dimension=1024"
})
class DialogueGraphIntegrationTest {
    @org.springframework.beans.factory.annotation.Autowired DialogueOrchestrator orchestrator;
    @Test
    void graphRouteExecutesResearchWriterReviewNodes() {
        var result = orchestrator.execute(new ChatRequest(
                "t-graph", "u", "根据知识库研究并审校", null, null, null,
                List.of(), List.of(), "graph"));
        assertEquals(TaskStatus.SUCCEEDED, result.status());
        assertEquals("review", result.current_node());
        assertEquals(3, result.node_runs().size());
    }
}
