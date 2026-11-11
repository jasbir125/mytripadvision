package com.mytripadvision;

import static org.assertj.core.api.Assertions.assertThat;

import com.mytripadvision.repository.KnowledgeChunkRepository;
import com.mytripadvision.support.AbstractIT;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class MigrationIT extends AbstractIT {

    @Autowired Flyway flyway;
    @Autowired JdbcClient jdbc;
    @Autowired KnowledgeChunkRepository knowledge;

    @Test
    void allMigrationsApplied_andNoneIsPending() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway.info().applied()).isNotEmpty();
    }

    @Test
    void pgvectorIsInstalled_andKnowledgeBaseIsIndexedAtStartup() {
        String extension = jdbc.sql("SELECT extname FROM pg_extension WHERE extname = 'vector'")
                .query(String.class).single();
        assertThat(extension).isEqualTo("vector");
        assertThat(knowledge.count()).isGreaterThan(10);
    }
}
