package com.mytripadvision.client;

import com.mytripadvision.config.HttpClients;
import com.mytripadvision.config.AppProperties;
import com.mytripadvision.exception.ExternalServiceException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** Tavily web search (used for hotels). */
@Component
public class TavilyClient {

    /** One search result. */
    public record Document(String title, String url, String content) {
    }

    private final AppProperties.Http config;
    private final RestClient rest;

    public TavilyClient(AppProperties props) {
        this.config = props.tavily();
        this.rest = HttpClients.create(config.baseUrl(), config.connectTimeout(), config.readTimeout());
    }

    public List<Document> search(String query) {
        if (config.apiKey() == null || config.apiKey().isBlank()) {
            throw new ExternalServiceException("tavily", "API key is not configured");
        }
        int maxResults = config.maxResults() == null ? 5 : config.maxResults();
        JsonNode body = HttpClients.callWithRetry("tavily", () -> rest.post()
                .uri("/search")
                .header("Authorization", "Bearer " + config.apiKey())
                .body(Map.of("query", query, "search_depth", "basic", "max_results", maxResults,
                        "include_raw_content", false))
                .retrieve().body(JsonNode.class));

        // results: [{"title": "...", "url": "...", "content": "...", "raw_content": "..."}]
        List<Document> documents = new ArrayList<>();
        if (body != null) {
            for (JsonNode result : body.path("results")) {
                String raw = result.path("raw_content").asString("");
                String content = raw.isBlank() ? result.path("content").asString("") : raw;
                if (!content.isBlank()) {
                    documents.add(new Document(result.path("title").asString(""), result.path("url").asString(""), content));
                }
            }
        }
        return documents;
    }
}
