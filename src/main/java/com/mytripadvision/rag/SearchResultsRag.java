package com.mytripadvision.rag;

import com.mytripadvision.client.TavilyClient;
import com.mytripadvision.config.AppProperties;
import com.mytripadvision.model.RagChunk;
import com.mytripadvision.repository.RagChunkRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * RAG over live search results (used for hotels): chunk, embed, store in pgvector keyed by request,
 * keep only the chunks closest to the question.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SearchResultsRag {

    private final EmbeddingClient embeddings;
    private final RagChunkRepository chunks;
    private final AppProperties props;

    /** @throws IllegalArgumentException when the documents hold no text */
    public String retrieveRelevant(List<TavilyClient.Document> documents, String query, String source) {
        AppProperties.Rag rag = props.rag();
        List<RagChunk> rows = new ArrayList<>();
        for (TavilyClient.Document d : documents) {
            for (String piece : chunkText(d.content(), rag.chunkChars(), rag.chunkOverlap())) {
                rows.add(new RagChunk(d.title(), d.url(), piece));
            }
        }
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("no text to index");
        }
        String requestId = UUID.randomUUID().toString().replace("-", "");
        List<float[]> vectors = embeddings.embed(rows.stream().map(r -> r.title() + ". " + r.content()).toList());
        chunks.saveAll(requestId, source, rows, vectors);

        List<RagChunkRepository.Scored> top = chunks.nearest(requestId, embeddings.embedOne(query), rag.hotelTopK());
        log.info("[rag] {}: indexed {} chunks from {} docs, kept top {} (best score {})", source, rows.size(),
                documents.size(), top.size(), top.isEmpty() ? "n/a" : String.format(Locale.ROOT, "%.2f", top.get(0).score()));
        return top.stream().map(t -> "[" + t.title() + "](" + t.url() + ")\n" + t.content())
                .collect(Collectors.joining("\n\n"));
    }

    /** Splits text into overlapping chunks after collapsing whitespace. */
    public static List<String> chunkText(String text, int size, int overlap) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        List<String> out = new ArrayList<>();
        if (clean.isEmpty()) {
            return out;
        }
        int start = 0;
        while (start < clean.length()) {
            out.add(clean.substring(start, Math.min(clean.length(), start + size)));
            if (start + size >= clean.length()) {
                break;
            }
            start += size - overlap;
        }
        return out;
    }
}
