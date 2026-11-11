package com.mytripadvision.repository;

/** A search-result chunk found by a vector search; score is the cosine similarity (1 = identical). */
public record SearchMatch(String title, String url, String content, double score) {
}
