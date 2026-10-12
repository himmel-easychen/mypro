package com.workpro.dialogue;
import com.workpro.agent.AgentContracts.*; import com.workpro.domain.Contracts.*; import com.workpro.runtime.AgentGraphService; import org.springframework.stereotype.Service; import java.util.*;
@Service public class DialogueOrchestrator {
 private final DialogueRouter router; private final WriterAgent writer; private final ReviewAgent review; private final AgentGraphService graph; private final AnswerService answers;
 public DialogueOrchestrator(DialogueRouter r,WriterAgent w,ReviewAgent v,AgentGraphService g,AnswerService answers){router=r;writer=w;review=v;graph=g;this.answers=answers;}
 public AggregatedTaskResult execute(ChatRequest r){var route=router.route(r.force_mode(),r.query(),router.inspect(r.query())); if(route==Route.RAG) return answer(r); if(route!=Route.CHAT){var result=graph.execute(r.task_id(),r.user_id(),Objects.requireNonNullElse(r.query(),""),route==Route.GRAPH||route==Route.DOCUMENT,route==Route.DOCUMENT);var e=result.context();var refs=e.<List<EvidenceRef>>get("evidenceRefs");return new AggregatedTaskResult(r.task_id(),TaskStatus.valueOf(result.execution().status().name()),result.execution().currentNode(),e.get("answer"),null,result.artifacts(),refs==null?List.of():refs,result.execution().snapshots().stream().map(s->new NodeRunSummary(s.nodeId(),s.status(),s.errorCode(),s.durationMs(),s.message())).toList());}var answer=writer.write(r.query(),List.of());var checked=review.review(answer);return new AggregatedTaskResult(r.task_id(),"success".equals(checked.status())?TaskStatus.SUCCEEDED:TaskStatus.BLOCKED,"writer",answer,checked.code(),List.of(),List.of(),List.of());}
 private AggregatedTaskResult answer(ChatRequest r) {
  var a=answers.answer(r.query(),r.doc_id(),r.chapter_id(),r.conversation_history(),false);
  var refs=a.evidence().stream().filter(e->a.citations().contains(e.id())).map(e->new EvidenceRef(e.id(),e.documentId(),r.chapter_id(),e.sourceFile(),e.location(),e.chunkId(),1.0)).toList();
  return new AggregatedTaskResult(r.task_id(),"OK".equals(a.status())?TaskStatus.SUCCEEDED:TaskStatus.FAILED,"answer",a.answer(),a.error(),List.of(),refs,List.of(new NodeRunSummary("answer",a.status(),a.error(),a.latencyMs(),a.refusalReason())));
 }
}
