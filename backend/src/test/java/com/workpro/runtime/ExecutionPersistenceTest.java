package com.workpro.runtime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ExecutionPersistenceTest {
    @Autowired
    ExecutionPersistence persistence;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void repeatedNodeExecutionUsesNextAttempt() {
        var run = persistence.startTaskRun(
                "attempt-test-" + UUID.randomUUID(),
                "query",
                "graph");

        persistence.startNodeRun(run.taskRunPk(), "research", "AGENT");
        persistence.startNodeRun(run.taskRunPk(), "research", "AGENT");

        Integer maxAttempt = jdbc.queryForObject(
                "select max(attempt) from node_run where task_run_id=? and node_name='research'",
                Integer.class,
                run.taskRunPk());
        assertEquals(2, maxAttempt);
    }
}
