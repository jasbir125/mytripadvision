package com.mytripadvision.repository;

import com.mytripadvision.model.RagChunk;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

/** Search-result chunks, keyed by request id. The vector search is JPQL: {@code cosine_distance} comes from hibernate-vector. */
public interface RagChunkRepository extends JpaRepository<RagChunk, Long> {

    /** The chunks of one request closest to the query vector, best first. */
    @Query("""
            select new com.mytripadvision.repository.SearchMatch(
                c.title, c.url, c.content, 1 - cosine_distance(c.embedding, :query))
            from RagChunk c
            where c.requestId = :requestId
            order by cosine_distance(c.embedding, :query)
            """)
    List<SearchMatch> nearest(String requestId, float[] query, Limit limit);

    /** @return how many expired chunks were removed */
    @Transactional
    @Modifying
    @Query("delete from RagChunk c where c.createdAt < :before")
    int deleteCreatedBefore(Instant before);
}
