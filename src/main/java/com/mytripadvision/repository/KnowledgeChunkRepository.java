package com.mytripadvision.repository;

import com.mytripadvision.model.KnowledgeChunk;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/** The knowledge-base index. The vector search is JPQL: {@code cosine_distance} comes from hibernate-vector. */
public interface KnowledgeChunkRepository extends JpaRepository<KnowledgeChunk, Long> {

    /** The sections closest to the query vector, best first. */
    @Query("""
            select new com.mytripadvision.repository.KnowledgeMatch(
                c.title, c.heading, c.content, 1 - cosine_distance(c.embedding, :query))
            from KnowledgeChunk c
            order by cosine_distance(c.embedding, :query)
            """)
    List<KnowledgeMatch> nearest(float[] query, Limit limit);

    /**
     * A PostgreSQL transaction lock, so two replicas that start at the same time rebuild the index one after
     * the other instead of writing duplicate rows. It is the one statement JPQL cannot express.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(:key)", nativeQuery = true)
    int lockForRebuild(long key);
}
