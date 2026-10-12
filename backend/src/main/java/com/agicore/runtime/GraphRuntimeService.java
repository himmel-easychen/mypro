package com.agicore.runtime;

import com.agicore.dialogue.*;
import com.agicore.domain.Contracts.*;
import com.agicore.rag.RetrievalContracts.*;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.*;

@Service
public class GraphRuntimeService {
    private final RetrievalService retrieval;
    private final WriterAgent writer;
    private final ReviewAgent review;
    private final DocumentExportService exporter;
    public GraphRuntimeService(RetrievalService r, WriterAgent w, ReviewAgent v, DocumentExportService e){retrieval=r;writer=w;review=v;exporter=e;}
    public Result execute(String taskId, String query, boolean document) {
        var evidence = new ArrayList<String>(); var refs = new ArrayList<EvidenceRef>(); var answer = new String[]{""};
        var runtime = StaticGraphRuntime.facade();
        var steps = new ArrayList<StaticGraphRuntime.Step>();
        steps.add(new StaticGraphRuntime.Step("research", 0, Duration.ofSeconds(30), () -> { var p=retrieval.retrieve(new RetrievalRequest(query,null,null,8,Map.of())); evidence.addAll(p.results().stream().map(RetrievalResult::content_preview).toList()); refs.addAll(p.evidenceRefs()); return "evidence="+evidence.size(); }));
        steps.add(new StaticGraphRuntime.Step("writer", 0, Duration.ofSeconds(30), () -> { answer[0]=writer.write(query,evidence); return answer[0]; }));
        steps.add(new StaticGraphRuntime.Step("review", 0, Duration.ofSeconds(30), () -> { var r=review.review(answer[0]); if(!"success".equals(r.status())) throw new IllegalStateException(r.message()); return r.message(); }));
        var run=runtime.execute(taskId,"research-writing-review",query,steps);
        List<ArtifactPath> artifacts=List.of();
        if(document && "SUCCEEDED".equals(run.status())) try { artifacts=List.of(exporter.exportDocx(taskId,answer[0])); } catch(Exception e){ return new Result(TaskStatus.FAILED,run.currentNode(),e.getMessage(),List.of(),refs,run.snapshots()); }
        return new Result(TaskStatus.valueOf(run.status()),run.currentNode(),answer[0],artifacts,refs,run.snapshots());
    }
    public record Result(TaskStatus status,String currentNode,String message,List<ArtifactPath> artifacts,List<EvidenceRef> refs,List<NodeSnapshot> snapshots) {}
}
