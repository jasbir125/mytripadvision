package com.mytripadvision.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
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
     */
    public record Llm(String provider, String model, String finalModel, String region,
                      String openaiApiKey, String groqApiKey,
                      @NotBlank String openaiBaseUrl, @NotBlank String groqBaseUrl,
                      Duration connectTimeout, Duration readTimeout) {

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

        /** AWS_REGION, or us-east-1. Only used by bedrock. */
        public String resolvedRegion() {
            return region == null || region.isBlank() ? "us-east-1" : region;
        }

        public String resolvedModel() {
            if (model != null && !model.isBlank()) {
                return model;
            }
            if (isBedrock()) {
                return "us.amazon.nova-lite-v1:0";
            }
            return isOpenai() ? "gpt-4o-mini" : "openai/gpt-oss-120b";
        }

        /**
         * The model that writes the final answer: LLM_FINAL_MODEL. When it is not set, openai uses gpt-4o and
         * bedrock uses Nova Pro (a stronger model than the one for the agents); groq uses the normal model.
         */
        public String resolvedFinalModel() {
            if (finalModel != null && !finalModel.isBlank()) {
                return finalModel;
            }
            if (isOpenai()) {
                return "gpt-4o";
            }
            return isBedrock() ? "us.amazon.nova-pro-v1:0" : resolvedModel();
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
