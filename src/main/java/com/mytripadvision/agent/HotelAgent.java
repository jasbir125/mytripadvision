package com.mytripadvision.agent;

import com.mytripadvision.client.TavilyClient;
import com.mytripadvision.exception.ExternalServiceException;
import com.mytripadvision.rag.SearchResultsRag;
import com.mytripadvision.workflow.WorkflowState;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Hotel search with Tavily, trimmed to the most relevant chunks (RAG). Makes no LLM call itself.
 * When the search service is down the plan goes on without hotels, and the user gets a one-line notice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class HotelAgent {

    /** What the later agents read instead of hotel results when the search could not run. */
    static final String NO_SEARCH_TEXT = "Hotel search unavailable: no hotel suggestions.";

    private final TavilyClient tavily;
    private final SearchResultsRag rag;

    public String run(WorkflowState state) {
        log.info("[hotel] started");
        List<TavilyClient.Document> documents;
        try {
            documents = tavily.search("Best hotels for " + state.getUserQuery());
        } catch (ExternalServiceException ex) {
            log.warn("[hotel] search failed: {}", ex.getMessage());
            state.addNotice("hotel", Outage.reason(ex) + ": there are no hotel suggestions in this plan.");
            return NO_SEARCH_TEXT;
        }

        String result;
        try {
            result = rag.retrieveRelevant(documents,
                    state.getUserQuery() + " hotel price per night location rating amenities", "hotel");
        } catch (RuntimeException ex) {
            // RAG is a bonus: without it the raw search results are used
            log.warn("[hotel] RAG failed ({}); using raw results", ex.getClass().getSimpleName());
            result = plainText(documents);
        }
        log.info("[hotel] done, {} chars passed to the LLM", result.length());
        return result;
    }

    /** Every search result as "[title](url)" followed by its text. */
    private static String plainText(List<TavilyClient.Document> documents) {
        StringBuilder text = new StringBuilder();
        for (TavilyClient.Document d : documents) {
            text.append("[").append(d.title()).append("](").append(d.url()).append(")\n")
                    .append(d.content()).append("\n\n");
        }
        return text.toString().strip();
    }
}
