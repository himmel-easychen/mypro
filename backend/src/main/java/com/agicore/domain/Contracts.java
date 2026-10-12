package com.agicore.domain;
import java.util.*;
public final class Contracts { private Contracts(){}
 public enum Route { CHAT,RAG,GRAPH,DOCUMENT } public enum TaskStatus { DRAFT,RUNNING,BLOCKED,SUCCEEDED,FAILED,ARCHIVED }
 public record ChatRequest(String task_id,String user_id,String query,String timestamp,String doc_id,String chapter_id,List<Object> attachments,List<Object> conversation_history,String force_mode){}
 public record NodeRunSummary(String node_name,String status,String error_code,long duration_ms,String summary){}
 public record EvidenceRef(String evidence_id,String doc_id,String chapter_id,String source_file,String location,String hash_or_id,double confidence){}
 public record ArtifactPath(String artifact_id,String type,String path,String checksum,String created_at){}
 public record AggregatedTaskResult(String task_id,TaskStatus status,String current_node,String message,String error_code,List<ArtifactPath> artifact_paths,List<EvidenceRef> evidence_refs,List<NodeRunSummary> node_runs){}
 public record ChunkRecord(String chunk_id,String parent_chunk_id,String doc_id,String source_type,String content,String chunk_role,String location,String source_anchor,String embedding_ref,String index_ref,double confidence,String evidence_type){}
 public record RetrievalResult(String chunk_id,String parent_chunk_id,double score,Map<String,Double> score_breakdown,String content_preview,String location,String source_file,String source_type,String evidence_type){}
}
