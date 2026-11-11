package com.mytripadvision.model;

/** One section of a knowledge-base document (stored with its embedding in {@code knowledge_chunks}). */
public record KnowledgeChunk(String source, String title, String heading, String content) {
}
