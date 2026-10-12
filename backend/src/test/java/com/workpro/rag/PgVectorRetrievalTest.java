package com.workpro.rag;

import com.workpro.persistence.RagPersistence;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PgVectorRetrievalTest {
    @Test
    void buildsNativePgvectorLiteralWithoutUsingMockIndex() {
        assertEquals("[0.1,0.2,-0.3]", RagPersistence.pgVectorLiteral(new double[]{0.1, 0.2, -0.3}));
        assertTrue(RagPersistence.VECTOR_SEARCH_SQL.contains("<=>"));
        assertTrue(RagPersistence.VECTOR_SEARCH_SQL.contains("embedding"));
    }

    @Test
    void emptyVectorDoesNotBecomeAValidDatabaseQuery() {
        var persistence = new RagPersistence((JdbcTemplate) null);
        var result = persistence.searchByVector(new double[0], 8, null);
        assertEquals(List.of(), result);
    }
}
