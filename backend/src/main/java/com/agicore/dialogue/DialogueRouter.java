package com.agicore.dialogue;
import com.agicore.domain.Contracts.*; import org.springframework.stereotype.Service;
@Service public class DialogueRouter {
 public Route route(String force,String query,ListFlag flags){ if(force!=null&&!force.isBlank()) return Route.valueOf(force.toUpperCase()); if(flags.document) return Route.DOCUMENT; if(flags.graph) return Route.GRAPH; if(flags.rag) return Route.RAG; return Route.CHAT; }
 public record ListFlag(boolean document,boolean graph,boolean rag){}
 public ListFlag inspect(String q){ String s=q==null?"":q.toLowerCase(); return new ListFlag(s.contains("word")||s.contains("excel")||s.contains("pdf")||s.contains("文档")||s.contains("导出"),s.contains("研究")&&s.contains("审校"),s.contains("根据资料")||s.contains("知识库")||s.contains("证据")||s.contains("检索")); }
}
