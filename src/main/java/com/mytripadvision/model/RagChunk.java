package com.mytripadvision.model;

/** One piece of a search result stored for one request (table {@code rag_chunks}). */
public record RagChunk(String title, String url, String content) {
}
