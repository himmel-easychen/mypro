package com.agicore.dialogue;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class AnswerPolicyTest {
 private final AnswerPolicy policy=new AnswerPolicy();
 private final List<RagPromptBuilder.Evidence> evidence=List.of(new RagPromptBuilder.Evidence("E1","c1","d1","a.pdf","p1","工位5个",null,""));
 @Test void emptyEvidenceRefuses(){assertEquals(AnswerPolicy.Reason.NO_EVIDENCE,policy.assess(List.of(),null));assertFalse(AnswerPolicy.REFUSAL.isBlank());}
 @Test void irrelevantOrUnmentionedFactsRefuse(){
  // expected.answer=未提及 is an evaluation expectation, never a runtime input.
  assertEquals(AnswerPolicy.Reason.MODEL_UNDETERMINED,policy.assess(evidence,false));
 }
 @Test void sufficientFactsDoNotRequireExpectedWording(){assertEquals(AnswerPolicy.Reason.NONE,policy.assess(evidence,true));assertEquals(AnswerPolicy.Reason.NONE,policy.assess(evidence,null));}
}
