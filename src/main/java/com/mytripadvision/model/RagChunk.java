package com.mytripadvision.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One piece of a search result, stored for one request (table {@code rag_chunks}, deleted after the retention period). */
@Entity
@Table(name = "rag_chunks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RagChunk {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "request_id", nullable = false, length = 64)
    private String requestId;

    @Column(name = "source", nullable = false, length = 50)
    private String source;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "url", nullable = false)
    private String url;

    @Column(name = "content", nullable = false)
    private String content;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = KnowledgeChunk.DIMENSIONS)
    @Column(name = "embedding", nullable = false)
    private float[] embedding;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public RagChunk(String title, String url, String content) {
        this.title = title;
        this.url = url;
        this.content = content;
    }

    /** Ties the chunk to the request it was fetched for and stores its embedding; done just before saving. */
    public void index(String requestId, String source, float[] embedding) {
        this.requestId = requestId;
        this.source = source;
        this.embedding = embedding;
    }
}
