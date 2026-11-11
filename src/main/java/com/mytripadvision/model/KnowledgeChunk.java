package com.mytripadvision.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One section of a knowledge-base document, stored with its embedding (table {@code knowledge_chunks}). */
@Entity
@Table(name = "knowledge_chunks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KnowledgeChunk {

    public static final int DIMENSIONS = 384;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source", nullable = false, length = 200)
    private String source;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "heading", nullable = false)
    private String heading;

    @Column(name = "content", nullable = false)
    private String content;

    /** The pgvector column; set once the text has been embedded. */
    @Setter
    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = DIMENSIONS)
    @Column(name = "embedding", nullable = false)
    private float[] embedding;

    public KnowledgeChunk(String source, String title, String heading, String content) {
        this.source = source;
        this.title = title;
        this.heading = heading;
        this.content = content;
    }
}
