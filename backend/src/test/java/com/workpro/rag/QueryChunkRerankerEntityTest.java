package com.workpro.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QueryChunkRerankerEntityTest {
    @Test
    void scoresOnlyExplicitLongEntitiesAndYears() {
        assertEquals(.12, QueryChunkReranker.entityScore(
                "比较《车辆维修合同》2023年的金额", "车辆维修合同2023年金额"), .0001);
        assertEquals(0, QueryChunkReranker.entityScore("项目金额是多少", "另一个项目"), .0001);
    }
}
