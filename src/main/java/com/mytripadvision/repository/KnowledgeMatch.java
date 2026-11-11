package com.mytripadvision.repository;

/** A knowledge-base section found by a vector search; score is the cosine similarity (1 = identical). */
public record KnowledgeMatch(String title, String heading, String content, double score) {
}
