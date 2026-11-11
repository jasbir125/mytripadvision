package com.mytripadvision.rag;

import com.mytripadvision.config.AppProperties;
import com.mytripadvision.model.KnowledgeChunk;
import com.mytripadvision.repository.KnowledgeChunkRepository;
import com.mytripadvision.repository.KnowledgeMatch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Our own travel documents, searchable by meaning. Every .md file in the knowledge location is split into
 * sections (one per "## heading"), embedded and stored in pgvector at startup; the itinerary agent searches it.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnowledgeBaseService {

    private static final long INDEX_LOCK = 7_301_001L;

    private final ResourcePatternResolver resources;
    private final EmbeddingClient embeddings;
    private final KnowledgeChunkRepository repository;
    private final AppProperties props;

    /** Rebuilds the index from the files (they are the source of truth). */
    @Transactional
    public int buildIndex() {
        List<KnowledgeChunk> sections = loadSections();
        if (sections.isEmpty()) {
            log.info("[knowledge] no documents found, nothing to index");
            return 0;
        }
        embedAll(sections);
        repository.lockForRebuild(INDEX_LOCK);   // replicas starting together rebuild one after the other
        repository.deleteAllInBatch();
        repository.saveAll(sections);
        log.info("[knowledge] indexed {} sections from {} files", sections.size(),
                sections.stream().map(KnowledgeChunk::getSource).distinct().count());
        return sections.size();
    }

    /** Embeds title + heading + text, so the vector knows which city and topic the section belongs to. */
    private void embedAll(List<KnowledgeChunk> sections) {
        List<String> texts = new ArrayList<>();
        for (KnowledgeChunk section : sections) {
            texts.add(section.getTitle() + " - " + section.getHeading() + "\n" + section.getContent());
        }
        List<float[]> vectors = embeddings.embed(texts);
        for (int i = 0; i < sections.size(); i++) {
            sections.get(i).setEmbedding(vectors.get(i));
        }
    }

    /** @return the sections most relevant to the query as text, or "" when none scores above the threshold */
    public String search(String query) {
        double minScore = props.rag().minScore();
        Limit topK = Limit.of(props.rag().knowledgeTopK());
        List<KnowledgeMatch> rows = repository.nearest(embeddings.embedOne(query), topK);
        List<String> relevant = new ArrayList<>();
        for (KnowledgeMatch row : rows) {
            boolean used = row.score() >= minScore;
            log.info("[knowledge] {} > {} (score {}) {}", row.title(), row.heading(),
                    String.format(Locale.ROOT, "%.2f", row.score()), used ? "used" : "ignored (score too low)");
            if (used) {
                relevant.add("### " + row.title() + " - " + row.heading() + "\n" + row.content());
            }
        }
        if (relevant.isEmpty()) {
            log.info("[knowledge] no relevant knowledge found for this request");
        }
        return String.join("\n\n", relevant);
    }

    List<KnowledgeChunk> loadSections() {
        String location = props.rag().knowledgeLocation().replaceAll("/+$", "");
        List<KnowledgeChunk> all = new ArrayList<>();
        try {
            for (Resource file : markdownFiles(location)) {
                all.addAll(sectionsOf(file));
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read the knowledge base at " + location, ex);
        }
        return all;
    }

    /** The .md files under the location, in name order so the index is always built the same way. */
    private Resource[] markdownFiles(String location) throws IOException {
        Resource[] files = resources.getResources(location + "/**/*.md");
        Arrays.sort(files, Comparator.comparing(f -> String.valueOf(f.getFilename())));
        return files;
    }

    /** One chunk per "## heading" of the file. */
    private static List<KnowledgeChunk> sectionsOf(Resource file) throws IOException {
        String name = String.valueOf(file.getFilename());
        String text = removeFrontMatter(file.getContentAsString(StandardCharsets.UTF_8));
        String title = titleOf(name, text);
        List<KnowledgeChunk> chunks = new ArrayList<>();
        splitIntoSections(text).forEach((heading, content) -> chunks.add(new KnowledgeChunk(name, title, heading, content)));
        return chunks;
    }

    /** The "# Title" line at the top of the file, or else the file name without ".md". */
    static String titleOf(String fileName, String text) {
        String stripped = text.strip();
        if (stripped.startsWith("# ")) {
            return stripped.lines().findFirst().orElse("").substring(2).strip();
        }
        return fileName.replaceFirst("\\.md$", "");
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
        Map<String, String> sections = new LinkedHashMap<>();
        String heading = "Overview";
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            if (line.startsWith("## ")) {
                addSection(sections, heading, body.toString());
                heading = line.substring(3).strip();
                body = new StringBuilder();
            } else {
                body.append(line).append('\n');
            }
        }
        addSection(sections, heading, body.toString());
        return sections;
    }

    /** Empty sections are dropped; two sections with the same heading are joined. */
    private static void addSection(Map<String, String> sections, String heading, String body) {
        String content = body.strip();
        if (!content.isEmpty()) {
            sections.merge(heading, content, (a, b) -> a + "\n" + b);
        }
    }
}
