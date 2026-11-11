package com.mytripadvision.repository;

import com.mytripadvision.model.KnowledgeChunk;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** The knowledge-base index; a table without an entity because the vector column is handled in SQL. */
@Repository
@RequiredArgsConstructor
public class KnowledgeChunkRepository {

    private static final long INDEX_LOCK = 7_301_001L;

    public record Scored(String title, String heading, String content, double score) {
    }

    private final JdbcClient jdbc;

    /** Replaces the whole index. The advisory lock makes two starting replicas take turns instead of duplicating rows. */
    @Transactional
    public void replaceAll(List<KnowledgeChunk> chunks, List<float[]> vectors) {
        jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(INDEX_LOCK).query().singleValue();
        jdbc.sql("DELETE FROM knowledge_chunks").update();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk c = chunks.get(i);
            jdbc.sql("INSERT INTO knowledge_chunks (source, title, heading, content, embedding) "
                            + "VALUES (?, ?, ?, ?, CAST(? AS vector))")
                    .params(c.source(), c.title(), c.heading(), c.content(), VectorLiteral.of(vectors.get(i)))
                    .update();
        }
    }

    @Transactional(readOnly = true)
    public List<Scored> nearest(float[] query, int limit) {
        String literal = VectorLiteral.of(query);
        return jdbc.sql("SELECT title, heading, content, 1 - (embedding <=> CAST(? AS vector)) AS score "
                        + "FROM knowledge_chunks ORDER BY embedding <=> CAST(? AS vector) LIMIT ?")
                .params(literal, literal, limit)
                .query((rs, n) -> new Scored(rs.getString("title"), rs.getString("heading"),
                        rs.getString("content"), rs.getDouble("score")))
                .list();
    }

    @Transactional(readOnly = true)
    public long count() {
        return jdbc.sql("SELECT count(*) FROM knowledge_chunks").query(Long.class).single();
    }
}
