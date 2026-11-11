package com.mytripadvision.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mytripadvision.client.TavilyClient;
import com.mytripadvision.model.KnowledgeChunk;
import com.mytripadvision.model.RagChunk;
import com.mytripadvision.repository.KnowledgeChunkRepository;
import com.mytripadvision.repository.KnowledgeMatch;
import com.mytripadvision.repository.RagChunkRepository;
import com.mytripadvision.repository.SearchMatch;
import com.mytripadvision.support.AbstractIT;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.simple.JdbcClient;

class KnowledgeAndSearchRagIT extends AbstractIT {

    @Autowired KnowledgeBaseService knowledge;
    @Autowired KnowledgeChunkRepository knowledgeChunks;
    @Autowired SearchResultsRag searchRag;
    @Autowired RagChunkRepository ragChunks;
    @Autowired EmbeddingClient embeddings;
    @Autowired JdbcClient jdbc;

    @Test
    void rebuildingTheIndex_replacesRows_insteadOfDuplicatingThem() {
        long before = knowledgeChunks.count();

        int indexed = knowledge.buildIndex();
        knowledge.buildIndex();

        assertThat(indexed).isEqualTo((int) before);
        assertThat(knowledgeChunks.count()).isEqualTo(before);
    }

    @Test
    void aSectionIsFound_byItsOwnText_andFormattedForThePrompt() {
        KnowledgeChunk section = knowledge.loadSections().stream()
                .filter(s -> s.getSource().equals("paris.md")).findFirst().orElseThrow();
        String query = section.getTitle() + " - " + section.getHeading() + "\n" + section.getContent();

        String result = knowledge.search(query);

        assertThat(result).contains("### " + query);
    }

    @Test
    void anUnrelatedQuery_findsNothingAboveTheThreshold() {
        assertThat(knowledge.search("quantum chromodynamics lattice gauge")).isEmpty();
    }

    @Test
    void nearestChunks_comeBackBestFirst_withCosineScores() {
        List<KnowledgeMatch> top = knowledgeChunks.nearest(embeddings.embedOne("paris metro tickets"), Limit.of(3));

        assertThat(top).hasSize(3);
        assertThat(top).isSortedAccordingTo((a, b) -> Double.compare(b.score(), a.score()));
        assertThat(top.get(0).score()).isBetween(0.0, 1.0001);
    }

    @Test
    void hotelResults_areChunked_stored_andOnlyTheBestMatchesReturned() {
        var docs = List.of(
                new TavilyClient.Document("Pool Hotel", "https://example.com/a", "luxury hotel with pool and breakfast near the beach"),
                new TavilyClient.Document("Volcano", "https://example.com/b", "volcano geology magma basalt eruption " + "lava ".repeat(300)));

        String result = searchRag.retrieveRelevant(docs, "hotel with pool and breakfast", "hotel");

        assertThat(result).startsWith("[Pool Hotel](https://example.com/a)\nluxury hotel with pool");
        assertThat(result.split("\n\n").length).isLessThanOrEqualTo(6);
        assertThat(jdbc.sql("SELECT count(DISTINCT request_id) FROM rag_chunks WHERE source = 'hotel'")
                .query(Long.class).single()).isPositive();
    }

    @Test
    void documentsWithoutText_areRejected() {
        assertThatThrownBy(() -> searchRag.retrieveRelevant(List.of(), "hotel", "hotel"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void chunksOfAnotherRequest_areNeverReturned_andExpiredOnesAreDeleted() {
        float[] vector = embeddings.embedOne("alpha beta");
        ragChunks.save(chunk("req-a", "A", vector));
        ragChunks.save(chunk("req-b", "B", vector));
        jdbc.sql("UPDATE rag_chunks SET created_at = now() - interval '2 days' WHERE request_id = 'req-b'").update();

        assertThat(ragChunks.nearest("req-a", vector, Limit.of(5))).extracting(SearchMatch::title).containsExactly("A");
        int deleted = ragChunks.deleteCreatedBefore(Instant.now().minus(Duration.ofHours(24)));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(ragChunks.nearest("req-b", vector, Limit.of(5))).isEmpty();
        assertThat(ragChunks.nearest("req-a", vector, Limit.of(5))).hasSize(1);
    }

    private static RagChunk chunk(String requestId, String title, float[] vector) {
        RagChunk chunk = new RagChunk(title, "u", "alpha beta");
        chunk.index(requestId, "hotel", vector);
        return chunk;
    }
}
