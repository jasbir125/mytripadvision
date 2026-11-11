package com.mytripadvision.client;

import com.mytripadvision.config.HttpClients;
import com.mytripadvision.config.AppProperties;
import com.mytripadvision.exception.ExternalServiceException;
import com.mytripadvision.workflow.Usage;
import jakarta.annotation.PostConstruct;
import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import tools.jackson.databind.JsonNode;

/**
 * The LLM. OpenAI and Groq share one HTTP API; Bedrock is called through the AWS SDK (Converse API).
 * Both paths add the tokens used to {@link Usage} and fail with ExternalServiceException("llm", short reason).
 */
@Component
@Slf4j
public class LlmClient {

    private final AppProperties.Llm config;
    private final RestClient rest;                 // openai and groq
    private final BedrockRuntimeClient bedrock;    // bedrock only; null for the other providers

    public LlmClient(AppProperties props, @Nullable BedrockRuntimeClient bedrock) {
        this.config = props.llm();
        this.bedrock = bedrock;
        this.rest = config.isBedrock() ? null
                : HttpClients.create(config.baseUrl(), config.connectTimeout(), config.readTimeout());
    }

    @PostConstruct
    public void checkConfiguration() {
        String provider = config.resolvedProvider();
        if (!provider.equals("openai") && !provider.equals("groq") && !provider.equals("bedrock")) {
            throw new IllegalStateException("Unknown LLM_PROVIDER '" + provider + "'. Use 'openai', 'groq' or 'bedrock'.");
        }
        if (config.isBedrock()) {
            // no API key: the AWS SDK finds the credentials itself (ECS task role, env variables or profile)
            log.info("[llm] provider={} model={} final-answer model={} region={}", provider, config.resolvedModel(),
                    config.resolvedFinalModel(), config.resolvedRegion());
        } else if (config.apiKeyOrEmpty().isBlank()) {
            log.warn("[llm] no API key for provider {}; LLM calls will fail until it is set", provider);
        } else {
            log.info("[llm] provider={} model={} final-answer model={}", provider, config.resolvedModel(),
                    config.resolvedFinalModel());
        }
    }

    /**
     * Sends one prompt to the normal model and returns the reply text. The tokens used are added to
     * {@code usage}. {@code systemPrompt} may be null.
     */
    public String complete(Usage usage, String systemPrompt, String userPrompt) {
        return send(config.resolvedModel(), usage, systemPrompt, userPrompt);
    }

    /** Same as {@link #complete}, but with LLM_FINAL_MODEL (if set). Only the final answer uses this. */
    public String completeWithFinalModel(Usage usage, String systemPrompt, String userPrompt) {
        return send(config.resolvedFinalModel(), usage, systemPrompt, userPrompt);
    }

    private String send(String model, Usage usage, String systemPrompt, String userPrompt) {
        if (config.isBedrock()) {
            return sendToBedrock(model, usage, systemPrompt, userPrompt);
        }
        if (config.apiKeyOrEmpty().isBlank()) {
            throw new ExternalServiceException("llm", "API key is not configured");
        }
        List<Map<String, String>> messages = new ArrayList<>();
        if (systemPrompt != null) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.add(Map.of("role", "user", "content", userPrompt));

        JsonNode reply = HttpClients.callWithRetry("llm", () -> rest.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + config.apiKeyOrEmpty())
                .body(Map.of("model", model, "messages", messages))
                .retrieve().body(JsonNode.class));
        if (reply == null) {
            throw new ExternalServiceException("llm", "empty response");
        }

        // usage: {"prompt_tokens": 12, "completion_tokens": 30}
        usage.add(model, reply.path("usage").path("prompt_tokens").asInt(0),
                reply.path("usage").path("completion_tokens").asInt(0));
        return reply.path("choices").path(0).path("message").path("content").asString("");
    }

    /** Bedrock Converse API: the system prompt is a system block, the user prompt is one user message. */
    private String sendToBedrock(String model, Usage usage, String systemPrompt, String userPrompt) {
        ConverseRequest.Builder request = ConverseRequest.builder()
                .modelId(model)
                .messages(Message.builder().role(ConversationRole.USER).content(ContentBlock.fromText(userPrompt)).build());
        if (systemPrompt != null) {
            request.system(SystemContentBlock.fromText(systemPrompt));
        }
        ConverseRequest converseRequest = request.build();

        ConverseResponse response = HttpClients.callWithRetry("llm", () -> bedrock.converse(converseRequest));

        if (response.usage() != null) {
            usage.add(model, response.usage().inputTokens(), response.usage().outputTokens());
        }
        // the reply is a list of content blocks; we only use the text ones
        String text = "";
        for (ContentBlock block : response.output().message().content()) {
            if (block.text() != null) {
                text += block.text();
            }
        }
        return text;
    }
}
