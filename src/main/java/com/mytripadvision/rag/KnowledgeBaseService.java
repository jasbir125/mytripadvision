package com.mytripadvision.rag;

import com.mytripadvision.config.AppProperties;
import com.mytripadvision.model.KnowledgeChunk;
import com.mytripadvision.repository.KnowledgeChunkRepository;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Service;

/**
 * Our own travel documents, searchable by meaning. Every .md file in the knowledge location is split into
 * sections (one per "## heading"), embedded and stored in pgvector at startup; the itinerary agent searches it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    private final ResourcePatternResolver resources;
    private final EmbeddingClient embeddings;
    private final KnowledgeChunkRepository repository;
    private final AppProperties props;

    /** Rebuilds the index from the files (they are the source of truth). */
    public int buildIndex() {
        List<KnowledgeChunk> sections = loadSections();
        if (sections.isEmpty()) {
            log.info("[knowledge] no documents found, nothing to index");
            return 0;
        }
        // title + heading + text, so the vector knows which city and topic the section belongs to
        List<String> texts = sections.stream()
                .map(s -> s.title() + " - " + s.heading() + "\n" + s.content()).toList();
        repository.replaceAll(sections, embeddings.embed(texts));
        log.info("[knowledge] indexed {} sections from {} files", sections.size(),
                sections.stream().map(KnowledgeChunk::source).distinct().count());
        return sections.size();
    }

    /** @return the sections most relevant to the query as text, or "" when none scores above the threshold */
    public String search(String query) {
        AppProperties.Rag rag = props.rag();
        List<KnowledgeChunkRepository.Scored> rows = repository.nearest(embeddings.embedOne(query), rag.knowledgeTopK());
        List<KnowledgeChunkRepository.Scored> relevant = rows.stream().filter(r -> r.score() >= rag.minScore()).toList();
        for (KnowledgeChunkRepository.Scored r : rows) {
            log.info("[knowledge] {} > {} (score {}) {}", r.title(), r.heading(),
                    String.format(Locale.ROOT, "%.2f", r.score()),
                    r.score() >= rag.minScore() ? "used" : "ignored (score too low)");
        }
        if (relevant.isEmpty()) {
            log.info("[knowledge] no relevant knowledge found for this request");
        }
        return relevant.stream().map(r -> "### " + r.title() + " - " + r.heading() + "\n" + r.content())
                .collect(Collectors.joining("\n\n"));
    }

    List<KnowledgeChunk> loadSections() {
        String location = props.rag().knowledgeLocation().replaceAll("/+$", "");
        List<KnowledgeChunk> all = new ArrayList<>();
        try {
            Resource[] files = resources.getResources(location + "/**/*.md");
            Arrays.sort(files, Comparator.comparing(f -> String.valueOf(f.getFilename())));
            for (Resource file : files) {
                String text = removeFrontMatter(file.getContentAsString(StandardCharsets.UTF_8));
                String name = String.valueOf(file.getFilename());
                String title = name.replaceFirst("\\.md$", "");
                String stripped = text.strip();
                if (stripped.startsWith("# ")) {
                    title = stripped.lines().findFirst().orElse("# " + title).substring(2).strip();
                }
                for (Map.Entry<String, String> s : splitIntoSections(text).entrySet()) {
                    all.add(new KnowledgeChunk(name, title, s.getKey(), s.getValue()));
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the knowledge base at " + location, ex);
        }
        return all;
    }

    /** Drops the '--- ... ---' metadata block at the top of a file. */
    static String removeFrontMatter(String text) {
        if (text.startsWith("---")) {
            String[] parts = text.split("---", 3);
            return parts.length == 3 ? parts[2] : text;
        }
        return text;
    }

    /** Splits a markdown document into sections, one per '## heading' (text before the first one is "Overview"). */
    static Map<String, String> splitIntoSections(String text) {
        List<String[]> sections = new ArrayList<>();
        String heading = "Overview";
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            if (line.startsWith("## ")) {
                sections.add(new String[] {heading, body.toString()});
                heading = line.substring(3).strip();
                body = new StringBuilder();
            } else {
                body.append(line).append('\n');
            }
        }
        sections.add(new String[] {heading, body.toString()});

        Map<String, String> result = new LinkedHashMap<>();
        for (String[] s : sections) {
            String content = s[1].strip();
            if (!content.isEmpty()) {
                result.merge(s[0], content, (a, b) -> a + "\n" + b);
            }
        }
        return result;
    }
}
