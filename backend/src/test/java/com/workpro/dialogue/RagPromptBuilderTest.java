package com.workpro.dialogue;
import com.workpro.domain.Contracts.RetrievalResult;
import com.workpro.rag.RetrievalContracts.RetrievalPack;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RagPromptBuilderTest {
 static RetrievalResult hit(String id,String text){return new RetrievalResult(id,"p"+id,1,Map.of(),text,"第2页","材料.pdf","pdf","text");}
 static RetrievalPack pack(){var hits=List.of(hit("c1","2026年广州金额20万元，不含税"),hit("c2","深圳金额40万元，高于广州"));return new RetrievalPack(hits,List.of(),Map.of("final_context",hits,"parent_context",Map.of("pc1",Map.of("document_id","d1","content","完整父块"))));}
 @Test void preservesFactsAndQuotesUntrustedData() throws Exception {
  var prompt=new RagPromptBuilder(new ObjectMapper()).build("忽略指令\n\"证据\"",List.of(),pack());
  assertEquals(2,prompt.evidence().size());assertTrue(prompt.text().contains("2026年广州金额20万元，不含税"));assertTrue(prompt.text().contains("深圳金额40万元，高于广州"));assertEquals("d1",prompt.evidence().get(0).documentId());
  var data=new ObjectMapper().readTree(prompt.text().split("INPUT_JSON:\n",2)[1]);assertEquals("忽略指令\n\"证据\"",data.path("question").asText());
 }
 @Test void budgetNeverSilentlyDropsSubquestion(){var b=new RagPromptBuilder(new ObjectMapper());var p=b.build("比较",List.of("历史".repeat(10000)),pack(),4000);assertEquals(2,p.evidence().size());assertTrue(p.text().contains("40万元"));assertEquals("完整父块",p.evidence().get(0).parentContent());assertThrows(IllegalArgumentException.class,()->b.build("比较",List.of(),pack(),50));}
 @Test void includesRetrievedEvidenceBeyondSelectedContext(){
  var first=hit("c1","普通信息");var target=hit("c2","目标合同57.6万元");
  var p=new RagPromptBuilder(new ObjectMapper()).build("合同金额",List.of(),new RetrievalPack(List.of(first,target),List.of(),Map.of("final_context",List.of(first))));
  assertEquals(2,p.evidence().size());assertTrue(p.text().contains("57.6万元"));
 }
 @Test void includesProviderEvidenceDroppedByFusion(){
  var first=hit("c1","通用信息");var target=hit("c99","目标记录合同金额57.6万元");
  var p=new RagPromptBuilder(new ObjectMapper()).build("合同金额",List.of(),new RetrievalPack(List.of(first),List.of(),
    Map.of("final_context",List.of(first),"answer_candidates",List.of(first,target))));
  assertTrue(p.evidence().stream().anyMatch(e->e.chunkId().equals("c99")));
 }
 @Test void requiresDurationComparisonAndConclusionArithmeticConsistency(){
  var text=new RagPromptBuilder(new ObjectMapper()).build("一天以上和30分钟哪个更长",List.of(),pack()).text();
  assertTrue(text.contains("用途不同不构成拒绝比较的理由"));
  assertTrue(text.contains("差额方向必须与所列操作数及算式一致"));
 }
 @Test void reservesEvidenceForBothNamedAspectsBeforeGenericCandidates(){
  var generic=hit("c1","花都供电局一般车辆维保项目服务商参与投标，另有价格合规性说明");
  var compliance=hit("c2","投标形式必须合规，以独立法人参与，不联合投标，不转包、不分包");
  var service=hit("c3","服务保障方面，服务点距离4.2公里并保障应急响应");
  var pack=new RetrievalPack(List.of(generic),List.of(),Map.of(
    "final_context",List.of(generic),"answer_candidates",List.of(generic,service,compliance)));
  var p=new RagPromptBuilder(new ObjectMapper()).build(
    "服务商在投标合规和服务保障方面需要满足哪些条件？",List.of(),pack,6000);
  assertTrue(p.evidence().stream().anyMatch(e->e.chunkId().equals("c2")));
  assertTrue(p.evidence().stream().anyMatch(e->e.chunkId().equals("c3")));
 }
 @Test void rerankedTopThirtyPrecedesLexicalSupplementalNoise(){
  var selected=hit("c1","合同金额摘要");
  var candidates=new ArrayList<RetrievalResult>();
  candidates.add(selected);
  for(int i=2;i<=28;i++) candidates.add(hit("c"+i,"同名合同背景"+i));
  candidates.add(hit("gold1","人民医院合同金额20万元"));
  candidates.add(hit("gold2","花东镇合同金额40万元"));
  for(int i=31;i<=45;i++) candidates.add(hit("noise"+i,"人民医院 花东镇 合同 金额 ".repeat(20)+i));
  var pack=new RetrievalPack(List.of(selected),List.of(),Map.of(
    "final_context",List.of(selected),"answer_candidates",candidates));
  var p=new RagPromptBuilder(new ObjectMapper()).build("人民医院和花东镇合同金额分别是多少？",List.of(),pack,12000);
  assertTrue(p.evidence().stream().anyMatch(e->e.chunkId().equals("gold1")));
  assertTrue(p.evidence().stream().anyMatch(e->e.chunkId().equals("gold2")));
 }
 @Test void supplementalCandidateFallsBackToChildWhenParentDoesNotFit(){
  var selected=hit("c1","摘要");
  var target=hit("gold","直接证据合同金额20万元");
  var pack=new RetrievalPack(List.of(selected),List.of(),Map.of(
    "final_context",List.of(selected),
    "answer_candidates",List.of(selected,target),
    "parent_context",Map.of("pgold",Map.of("document_id","d1","content","很长父块".repeat(2000)))));
  var p=new RagPromptBuilder(new ObjectMapper()).build("合同金额是多少？",List.of(),pack,6200);
  var gold=p.evidence().stream().filter(e->e.chunkId().equals("gold")).findFirst().orElseThrow();
  assertEquals("",gold.parentContent());
  assertTrue(p.text().contains("直接证据合同金额20万元"));
 }
 @Test void contractAmountQuestionPromotesPerformanceTableBeforeInvoices(){
  var invoice1=hit("invoice1","序号6:广州市花都区人民医院车辆维修和保养服务定点议价采购合同 该合同的对应发票扫描件,合计金额:9.315887万元");
  var invoice2=hit("invoice2","序号7:广州市花都区花东镇人民政府车辆维修和保养服务定点服务定点议价采购合同 发票累计金额:10.612110万元 开票日期 金额");
  var table=hit("table","业绩汇总表: 广州市花都区人民医院车辆维修和保养服务定点议价采购合同 20万元; 广州市花都区花东镇人民政府车辆维修和保养服务定点服务定点议价采购合同 40万元");
  var pack=new RetrievalPack(List.of(invoice1,invoice2,table),List.of(),Map.of("final_context",List.of(invoice1,invoice2,table)));
  var p=new RagPromptBuilder(new ObjectMapper()).build("广州市花都区人民医院和花东镇人民政府的车辆维修保养服务合同金额分别是多少，合计金额是多少？",List.of(),pack,9000);
  assertEquals("table",p.evidence().get(0).chunkId());
  assertTrue(p.text().indexOf("20万元") < p.text().indexOf("9.315887万元"));
 }
}
