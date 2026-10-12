package com.agicore.dialogue;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceArithmeticTest {
    private RagPromptBuilder.Evidence evidence(String table) {
        return new RagPromptBuilder.Evidence("E1", "c", "d", "s", "p", table, "p1", table);
    }
    @Test void computesDifferentColumnsFromEvidenceWithoutQuestionOrGold() {
        var result = EvidenceArithmetic.summarize(List.of(evidence(
                "序号 | 岗位 | 甲类 | 乙类\n1 | 管理岗 | ≥3 | ≥1\n2 | 维修岗 | ≥4 | ≥2\n3 | 检验岗 | ≥2 | ≥1")));
        assertEquals(2, result.size());
        assertEquals("9", result.get(0).get("sum"));
        assertEquals("4", result.get(1).get("sum"));
        assertEquals(3, result.get(1).get("rowCount"));
    }
    @Test void missingRowsOrCellsCannotBecomeACompleteTotal() {
        assertTrue(EvidenceArithmetic.summarize(List.of(evidence(
                "序号 | 岗位 | 数量\n1 | 管理 | ≥2\n3 | 维修 | ≥4"))).isEmpty());
        assertTrue(EvidenceArithmetic.summarize(List.of(evidence(
                "序号 | 岗位 | 数量\n1 | 管理 | ≥2\n2 | 维修 | 未提及"))).isEmpty());
    }
    @Test void avoidsAddingContractAmountsOrDuplicateParentTables() {
        assertTrue(EvidenceArithmetic.summarize(List.of(evidence(
                "序号 | 合同 | 金额\n1 | 甲 | 20\n2 | 乙 | 40"))).isEmpty());
        var e = evidence("序号 | 岗位 | 数量\n1 | 管理 | ≥2\n2 | 维修 | ≥4");
        assertEquals(1, EvidenceArithmetic.summarize(List.of(e, e)).size());
    }
}
