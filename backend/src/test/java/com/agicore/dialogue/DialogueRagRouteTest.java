package com.agicore.dialogue;

import com.agicore.domain.Contracts.*;
import com.agicore.runtime.AgentGraphService;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DialogueRagRouteTest {
 @Test void ordinaryRagUsesAnswerServiceWithDocumentAndHistory() {
  var answers=mock(AnswerService.class);var writer=mock(WriterAgent.class);var graph=mock(AgentGraphService.class);
  List<Object> history=List.of("上一问");
  var evidence=new RagPromptBuilder.Evidence("E1","c1","d1","a.pdf","p2","5个",null,"");
  when(answers.answer("多少","d1",null,history,false)).thenReturn(new AnswerService.Answer("多少","5个[E1]",List.of("E1"),false,"NONE","OK",null,"prompt","raw",List.of(evidence),Map.of(),List.of("c1"),12,10));
  var orchestrator=new DialogueOrchestrator(new DialogueRouter(),writer,mock(ReviewAgent.class),graph,answers);
  var result=orchestrator.execute(new ChatRequest("t","u","多少",null,"d1",null,List.of(),history,"rag"));
  assertEquals(TaskStatus.SUCCEEDED,result.status());assertEquals("E1",result.evidence_refs().get(0).evidence_id());
  assertEquals("c1",result.evidence_refs().get(0).hash_or_id());verifyNoInteractions(writer,graph);
 }
}
