package com.mytripadvision.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** All MyTripAdvision settings, bound from the {@code mytripadvision.*} keys of application.yml. */
@Validated
@ConfigurationProperties(prefix = "mytripadvision")
public record AppProperties(
        Llm llm, Http aviationstack, Http tavily, Http openweather, Rag rag, Workflow workflow) {

    /**
     * Provider is openai, groq or bedrock. openai and groq need their API key at the first call; bedrock uses
     * the AWS credentials of the machine or ECS task (no key setting).
     * All model ids live in application.yml: {@code providers} holds each provider's default models and
     * {@code prices} what they cost; {@code model} and {@code finalModel} override the defaults when set.
     */
    public record Llm(String provider, String model, String finalModel, String region,
                      String openaiApiKey, String groqApiKey,
                      @NotBlank String openaiBaseUrl, @NotBlank String groqBaseUrl,
                      Duration connectTimeout, Duration readTimeout,
                      @NotEmpty Map<String, Models> providers, List<Price> prices,
                      String bedrockEndpoint) {

        /** A provider's default models: {@code model} runs the agents, {@code finalModel} writes the final answer. */
        public record Models(String model, String finalModel) {
        }

        /** USD per 1 million tokens of one model. */
        public record Price(@NotBlank String model, double input, double output) {
        }

        public boolean isOpenai() {
            return "openai".equals(resolvedProvider());
        }

        public String apiKeyOrEmpty() {
            String key = isOpenai() ? openaiApiKey : groqApiKey;
            return key == null ? "" : key;
        }

        public String baseUrl() {
            return isOpenai() ? openaiBaseUrl : groqBaseUrl;
        }

        public String resolvedProvider() {
            return provider == null ? "groq" : provider.toLowerCase();
        }

        public boolean isBedrock() {
            return "bedrock".equals(resolvedProvider());
        }

        /** BEDROCK_ENDPOINT: set only for a local emulator such as LocalStack or Floci; empty means real AWS. */
        public boolean hasBedrockEndpoint() {
            return hasText(bedrockEndpoint);
        }

        /** AWS_REGION, or us-east-1. Only used by bedrock. */
        public String resolvedRegion() {
            return region == null || region.isBlank() ? "us-east-1" : region;
        }

        /** LLM_MODEL, or the provider's default model from application.yml. */
        public String resolvedModel() {
            return hasText(model) ? model : defaults().model();
        }

        /**
         * The model that writes the final answer: LLM_FINAL_MODEL, or the provider's final-model from
         * application.yml. A provider without one uses the normal model for the final answer too.
         */
        public String resolvedFinalModel() {
            if (hasText(finalModel)) {
                return finalModel;
            }
            String fallback = defaults().finalModel();
            return hasText(fallback) ? fallback : resolvedModel();
        }

        /** The provider's entry under mytripadvision.llm.providers. */
        private Models defaults() {
            Models found = providers == null ? null : providers.get(resolvedProvider());
            if (found == null || !hasText(found.model())) {
                throw new IllegalStateException("No default model for provider '" + resolvedProvider()
                        + "': add it under mytripadvision.llm.providers in application.yml");
            }
            return found;
        }

        private static boolean hasText(String value) {
            return value != null && !value.isBlank();
        }
    }

    public record Http(String apiKey, @NotBlank String baseUrl, Duration connectTimeout, Duration readTimeout,
                       Integer maxResults) {
    }

    public record Rag(@NotBlank String knowledgeLocation, double minScore, @Positive int knowledgeTopK,
                      @Positive int hotelTopK, @Positive int chunkChars, int chunkOverlap,
                      Duration retention, Embedding embedding) {
        public record Embedding(@NotBlank String modelUrl, @NotBlank String tokenizerUrl, @NotBlank String cacheDir,
                                @Positive int maxTokens, @Positive int dimensions) {
        }
    }

    /** humanReview=false skips the approval step; maxRevisions change requests are handled before the plan goes out. */
    public record Workflow(boolean humanReview, @Positive int maxRevisions, @Positive int summaryEvery,
                           String defaultOriginIata) {
    }
}
