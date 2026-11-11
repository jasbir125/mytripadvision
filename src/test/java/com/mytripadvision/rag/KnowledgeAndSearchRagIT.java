package com.mytripadvision.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mytripadvision.client.TavilyClient;
import com.mytripadvision.model.KnowledgeChunk;
import com.mytripadvision.repository.KnowledgeChunkRepository;
import com.mytripadvision.repository.RagChunkRepository;
import com.mytripadvision.support.AbstractIT;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
                .filter(s -> s.source().equals("paris.md")).findFirst().orElseThrow();
        String query = section.title() + " - " + section.heading() + "\n" + section.content();

        String result = knowledge.search(query);

        assertThat(result).contains("### " + section.title() + " - " + section.heading() + "\n" + section.content());
    }

    @Test
    void anUnrelatedQuery_findsNothingAboveTheThreshold() {
        assertThat(knowledge.search("quantum chromodynamics lattice gauge")).isEmpty();
    }

    @Test
    void nearestChunks_comeBackBestFirst_withCosineScores() {
        List<KnowledgeChunkRepository.Scored> top = knowledgeChunks.nearest(embeddings.embedOne("paris metro tickets"), 3);

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
        ragChunks.saveAll("req-a", "hotel", List.of(new com.mytripadvision.model.RagChunk("A", "u", "alpha beta")), List.of(vector));
        ragChunks.saveAll("req-b", "hotel", List.of(new com.mytripadvision.model.RagChunk("B", "u", "alpha beta")), List.of(vector));
        jdbc.sql("UPDATE rag_chunks SET created_at = now() - interval '2 days' WHERE request_id = 'req-b'").update();

        assertThat(ragChunks.nearest("req-a", vector, 5)).extracting(RagChunkRepository.Scored::title).containsExactly("A");
        int deleted = ragChunks.deleteOlderThan(Duration.ofHours(24));

        assertThat(deleted).isGreaterThanOrEqualTo(1);
        assertThat(ragChunks.nearest("req-b", vector, 5)).isEmpty();
        assertThat(ragChunks.nearest("req-a", vector, 5)).hasSize(1);
    }
}
