package com.mytripadvision.client;

import com.mytripadvision.config.AppProperties;
import com.mytripadvision.config.HttpClients;
import com.mytripadvision.exception.ExternalServiceException;
import com.mytripadvision.workflow.Usage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

/** OpenAI and Groq: both speak the same chat-completions HTTP API, so one class serves both. */
public class ChatApiLlmClient implements LlmClient {

    private final AppProperties.Llm config;
    private final RestClient rest;

    public ChatApiLlmClient(AppProperties.Llm config) {
        this.config = config;
        this.rest = HttpClients.create(config.baseUrl(), config.connectTimeout(), config.readTimeout());
    }

    @Override
    public String complete(Usage usage, String systemPrompt, String userPrompt) {
        return send(config.resolvedModel(), usage, systemPrompt, userPrompt);
    }

    @Override
    public String completeWithFinalModel(Usage usage, String systemPrompt, String userPrompt) {
        return send(config.resolvedFinalModel(), usage, systemPrompt, userPrompt);
    }

    private String send(String model, Usage usage, String systemPrompt, String userPrompt) {
        if (config.apiKeyOrEmpty().isBlank()) {
            throw new ExternalServiceException("llm", "API key is not configured");
        }
        Map<String, Object> request = Map.of("model", model, "messages", chatMessages(systemPrompt, userPrompt));
        JsonNode reply = HttpClients.callWithRetry("llm", () -> rest.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + config.apiKeyOrEmpty())
                .body(request)
                .retrieve().body(JsonNode.class));
        if (reply == null) {
            throw new ExternalServiceException("llm", "empty response");
        }
        // usage: {"prompt_tokens": 12, "completion_tokens": 30}
        usage.add(model, reply.path("usage").path("prompt_tokens").asInt(0),
                reply.path("usage").path("completion_tokens").asInt(0));
        return reply.path("choices").path(0).path("message").path("content").asString("");
    }

    /** The system prompt (when there is one) followed by the user prompt. */
    private static List<Map<String, String>> chatMessages(String systemPrompt, String userPrompt) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));
        return messages;
    }
}
