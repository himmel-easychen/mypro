package com.workpro.dialogue;
import com.workpro.model.ModelGateway;
import com.workpro.model.RagGenerationGateway;
import com.workpro.rag.RetrievalContracts.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AnswerServiceTest {
 private final RetrievalService retrieval=mock(RetrievalService.class);
 private final RagGenerationGateway gateway=mock(RagGenerationGateway.class);
 private final ObjectMapper mapper=new ObjectMapper();
 private final AnswerService service=new AnswerService(retrieval,new RagPromptBuilder(mapper),new AnswerPolicy(),gateway,mapper);
 private AnswerService.Answer run(String response){when(retrieval.retrieve(any())).thenReturn(RagPromptBuilderTest.pack());when(gateway.complete(anyString())).thenReturn(new ModelGateway.ModelResponse(response,10,10,5));return service.answer("金额?","d1",null,List.of(),false);}
 @Test void mappedCitationsAndTrace(){var a=run("{\"supported\":true,\"answer\":\"广州20万元[E1]，深圳40万元[E2]。\"}");assertEquals("OK",a.status());assertEquals(List.of("E1","E2"),a.citations());assertFalse(a.prompt().isBlank());assertEquals(2,a.retrievedChunkIds().size());}
 @Test void invalidCitationsFail(){assertEquals("INVALID_CITATIONS",run("{\"supported\":true,\"answer\":\"20万元[E9]\"}").error());assertEquals("INVALID_CITATIONS",run("{\"supported\":true,\"answer\":\"20万元\"}").error());}
 @Test void bareCitationIdsAreNormalizedBeforeValidation(){
  var a=run("{\"supported\":true,\"answer\":\"广州20万元E1，深圳40万元E2。\"}");
  assertEquals("OK",a.status());
  assertEquals("广州20万元[E1]，深圳40万元[E2]。",a.answer());
  assertEquals(List.of("E1","E2"),a.citations());
 }
 @Test void stableRefusal(){var a=run("{\"supported\":false,\"answer\":\"未提及\"}");assertTrue(a.refusal());assertEquals(AnswerPolicy.REFUSAL,a.answer());}
 @Test void unitFailureRetriesOnceAndRetainsBothAttempts(){
  when(retrieval.retrieve(any())).thenReturn(RagPromptBuilderTest.pack());
  when(gateway.complete(anyString())).thenReturn(
    new ModelGateway.ModelResponse("{\"supported\":true,\"answer\":\"20分÷4分/年=5倍[E1]\"}",10,10,5),
    new ModelGateway.ModelResponse("{\"supported\":true,\"answer\":\"20分÷4分=5倍[E1]\"}",10,10,5));
  var a=service.answer("评分倍数",null,null,List.of(),false);
  assertEquals("OK",a.status());assertEquals(2,((List<?>)a.retrieval().get("generation_attempts")).size());
  verify(gateway,times(2)).complete(anyString());
 }
 @Test void repeatedUnitFailureIsNotAnAcceptedAnswer(){
  assertEquals("INVALID_ANSWER_UNITS",run("{\"supported\":true,\"answer\":\"20分÷4分/年=5倍[E1]\"}").error());
  verify(gateway,times(2)).complete(anyString());
 }
 @Test void malformedFunctionArgumentsCanRecoverWithoutDiscardingFailure(){
  when(retrieval.retrieve(any())).thenReturn(RagPromptBuilderTest.pack());
  when(gateway.complete(anyString())).thenReturn(
    new ModelGateway.ModelResponse("{broken",10,10,5),
    new ModelGateway.ModelResponse("{\"supported\":true,\"answer\":\"20万元[E1]\"}",10,10,5));
  var a=service.answer("金额",null,null,List.of(),false);
  assertEquals("OK",a.status());
  assertEquals(2,((List<?>)a.retrieval().get("generation_attempts")).size());
 }
 @Test void backgroundSupportDoesNotOverrideUnknownAskedFact(){
  assertTrue(run("{\"answerability\":\"NONE\",\"supported\":true,\"answer\":\"有相关背景[E1]，但服务期限未提及\"}").refusal());
  assertFalse(run("{\"answerability\":\"PARTIAL\",\"supported\":true,\"answer\":\"金额20万元[E1]，期限未提及\"}").refusal());
 }
 @Test void malformedAndMultipleObjectsAreContractErrors(){
  assertEquals("INVALID_MODEL_RESPONSE",run("{broken").error());
  assertEquals("INVALID_MODEL_RESPONSE",run("{\"supported\":true,\"answer\":\"20万元[E1]\"} {}").error());
  assertEquals("INVALID_MODEL_RESPONSE",run("{\"supported\":true,\"answer\":\"20万元[E1]\",\"answer\":\"FULL\"}").error());
 }
 @Test void emptyEvidenceSkipsModel(){when(retrieval.retrieve(any())).thenReturn(new RetrievalPack(List.of(),List.of(),Map.of()));assertTrue(service.answer("问题",null,null,List.of(),false).refusal());verifyNoInteractions(gateway);}
 @Test void failuresAreExplicit(){assertEquals("ERROR",run("null").status());when(retrieval.retrieve(any())).thenThrow(new IllegalStateException("offline"));var a=service.answer("问题",null,null,List.of(),false);assertEquals("ERROR",a.status());assertFalse(a.refusal());assertFalse(a.answer().isBlank());}
}
