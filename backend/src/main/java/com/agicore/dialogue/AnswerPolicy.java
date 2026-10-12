package com.agicore.dialogue;

import org.springframework.stereotype.Component;
import java.util.List;

/** Answer-layer contract. Evaluation labels are deliberately not an input. */
@Component
public class AnswerPolicy {
    public static final String REFUSAL = "根据已检索到的材料，未找到可以支持该问题的明确依据。";
    public enum Reason { NONE, NO_EVIDENCE, NO_ANSWER_BEARING_FACTS, MODEL_UNDETERMINED }

    public Reason assess(List<RagPromptBuilder.Evidence> evidence, Boolean supported) {
        if (evidence == null || evidence.isEmpty()) return Reason.NO_EVIDENCE;
        if (evidence.stream().allMatch(e -> e.content() == null || e.content().isBlank()))
            return Reason.NO_ANSWER_BEARING_FACTS;
        // Semantic sufficiency is decided from evidence by the model, never lexical overlap or gold.
        return Boolean.FALSE.equals(supported) ? Reason.MODEL_UNDETERMINED : Reason.NONE;
    }
}
