package com.mytripadvision.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mytripadvision.config.AppProperties;
import com.mytripadvision.repository.KnowledgeChunkRepository;
import com.mytripadvision.repository.KnowledgeChunkRepository.Scored;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

class KnowledgeBaseServiceTest {

    private final EmbeddingClient embeddings = mock(EmbeddingClient.class);
    private final KnowledgeChunkRepository repository = mock(KnowledgeChunkRepository.class);

    private KnowledgeBaseService service(String location) {
        var rag = new AppProperties.Rag(location, 0.66, 4, 6, 700, 100, Duration.ofHours(24), null);
        return new KnowledgeBaseService(new PathMatchingResourcePatternResolver(), embeddings, repository,
                new AppProperties(null, null, null, null, rag, null));
    }

    @Test
    void sectionsAreSplitAtLevelTwoHeadings_andEmptyOnesDropped() {
        var sections = KnowledgeBaseService.splitIntoSections("""
                intro text

                ## Best time
                spring

                ## Empty

                ## Transport
                metro""");

        assertThat(sections).containsOnlyKeys("Overview", "Best time", "Transport");
        assertThat(sections.get("Best time")).isEqualTo("spring");
    }

    @Test
    void frontMatterIsRemoved() {
        assertThat(KnowledgeBaseService.removeFrontMatter("---\ntags: a\n---\n# Title")).isEqualTo("\n# Title");
        assertThat(KnowledgeBaseService.removeFrontMatter("# Title")).isEqualTo("# Title");
    }

    @Test
    void bundledKnowledgeBase_isLoadedWithTitles() {
        var sections = service("classpath:knowledge_base").loadSections();

        assertThat(sections).isNotEmpty();
        assertThat(sections).anyMatch(s -> s.source().equals("paris.md") && s.title().contains("Paris"));
    }

    @Test
    void search_returnsOnlySectionsAboveTheScoreThreshold() {
        when(embeddings.embedOne(any())).thenReturn(new float[EmbeddingClient.DIMENSIONS]);
        when(repository.nearest(any(), anyInt())).thenReturn(List.of(
                new Scored("Paris Guide", "Transport", "Take the metro", 0.80),
                new Scored("Goa Guide", "Beaches", "Sand", 0.50)));

        String text = service("classpath:knowledge_base").search("Paris trip");

        assertThat(text).isEqualTo("### Paris Guide - Transport\nTake the metro");
    }

    @Test
    void search_returnsEmptyText_whenNothingIsRelevant() {
        when(embeddings.embedOne(any())).thenReturn(new float[EmbeddingClient.DIMENSIONS]);
        when(repository.nearest(any(), anyInt())).thenReturn(List.of(new Scored("Goa Guide", "Beaches", "Sand", 0.10)));

        assertThat(service("classpath:knowledge_base").search("Oslo")).isEmpty();
    }

    @Test
    void chunking_overlapsChunks_andCollapsesWhitespace() {
        List<String> chunks = SearchResultsRag.chunkText("abcdefghij  klmnop", 8, 2);

        assertThat(chunks).containsExactly("abcdefgh", "ghij klm", "lmnop");
        assertThat(SearchResultsRag.chunkText("   ", 8, 2)).isEmpty();
        assertThat(SearchResultsRag.chunkText("short", 8, 2)).containsExactly("short");
    }
}
