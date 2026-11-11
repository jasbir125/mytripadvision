package com.mytripadvision.repository;

import com.mytripadvision.model.RagChunk;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Search-result chunks, keyed by request id. */
@Repository
@RequiredArgsConstructor
public class RagChunkRepository {

    public record Scored(String title, String url, String content, double score) {
    }

    private final JdbcClient jdbc;

    @Transactional
    public void saveAll(String requestId, String source, List<RagChunk> chunks, List<float[]> vectors) {
        for (int i = 0; i < chunks.size(); i++) {
            RagChunk c = chunks.get(i);
            jdbc.sql("INSERT INTO rag_chunks (request_id, source, title, url, content, embedding) "
                            + "VALUES (?, ?, ?, ?, ?, CAST(? AS vector))")
                    .params(requestId, source, c.title(), c.url(), c.content(), VectorLiteral.of(vectors.get(i)))
                    .update();
        }
    }

    @Transactional(readOnly = true)
    public List<Scored> nearest(String requestId, float[] query, int limit) {
        String literal = VectorLiteral.of(query);
        return jdbc.sql("SELECT title, url, content, 1 - (embedding <=> CAST(? AS vector)) AS score "
                        + "FROM rag_chunks WHERE request_id = ? ORDER BY embedding <=> CAST(? AS vector) LIMIT ?")
                .params(literal, requestId, literal, limit)
                .query((rs, n) -> new Scored(rs.getString("title"), rs.getString("url"), rs.getString("content"),
                        rs.getDouble("score")))
                .list();
    }

    @Transactional
    public int deleteOlderThan(Duration age) {
        return jdbc.sql("DELETE FROM rag_chunks WHERE created_at < now() - make_interval(secs => ?)")
                .param(age.toSeconds()).update();
    }
}
