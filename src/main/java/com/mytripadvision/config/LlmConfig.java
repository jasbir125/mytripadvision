package com.mytripadvision.config;

import com.mytripadvision.client.BedrockLlmClient;
import com.mytripadvision.client.ChatApiLlmClient;
import com.mytripadvision.client.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;

/** Picks the LLM implementation for the configured provider, once, at startup. */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class LlmConfig {

    /** @param bedrock the AWS client; it only exists when the provider is bedrock (see {@link BedrockConfig}) */
    @Bean
    public LlmClient llmClient(AppProperties props, @Nullable BedrockRuntimeClient bedrock) {
        AppProperties.Llm llm = props.llm();
        String provider = llm.resolvedProvider();
        if (llm.isBedrock()) {
            log.info("[llm] provider={} model={} final-answer model={} region={}", provider, llm.resolvedModel(),
                    llm.resolvedFinalModel(), llm.resolvedRegion());
            return new BedrockLlmClient(llm, bedrock);
        }
        if (!provider.equals("openai") && !provider.equals("groq")) {
            throw new IllegalStateException("Unknown LLM_PROVIDER '" + provider + "'. Use 'openai', 'groq' or 'bedrock'.");
        }
        if (llm.apiKeyOrEmpty().isBlank()) {
            log.warn("[llm] no API key for provider {}; LLM calls will fail until it is set", provider);
        } else {
            log.info("[llm] provider={} model={} final-answer model={}", provider, llm.resolvedModel(), llm.resolvedFinalModel());
        }
        return new ChatApiLlmClient(llm);
    }
}
