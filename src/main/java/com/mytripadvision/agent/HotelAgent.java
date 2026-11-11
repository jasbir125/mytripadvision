package com.mytripadvision.agent;

import com.mytripadvision.client.TavilyClient;
import com.mytripadvision.rag.SearchResultsRag;
import com.mytripadvision.workflow.WorkflowState;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Hotel search with Tavily, trimmed to the most relevant chunks (RAG). Makes no LLM call itself. */
@Component
@RequiredArgsConstructor
@Slf4j
public class HotelAgent {

    private final TavilyClient tavily;
    private final SearchResultsRag rag;

    public String run(WorkflowState state) {
        log.info("[hotel] started");
        List<TavilyClient.Document> documents = tavily.search("Best hotels for " + state.getUserQuery());

        String result;
        try {
            result = rag.retrieveRelevant(documents,
                    state.getUserQuery() + " hotel price per night location rating amenities", "hotel");
        } catch (RuntimeException ex) {
            // RAG is a bonus: without it the raw search results are used
            log.warn("[hotel] RAG failed ({}); using raw results", ex.getClass().getSimpleName());
            result = "";
            for (TavilyClient.Document d : documents) {
                result += "[" + d.title() + "](" + d.url() + ")\n" + d.content() + "\n\n";
            }
            result = result.strip();
        }
        log.info("[hotel] done, {} chars passed to the LLM", result.length());
        return result;
    }
}
