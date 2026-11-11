package com.mytripadvision.client;

import com.mytripadvision.workflow.Usage;

/**
 * The language model, as the agents see it. Which provider answers is decided once at startup
 * ({@code mytripadvision.llm.provider}); every implementation adds the tokens used to {@link Usage} and fails
 * with {@code ExternalServiceException("llm", short reason)}.
 */
public interface LlmClient {

    /** Sends one prompt to the normal model and returns the reply text. {@code systemPrompt} may be null. */
    String complete(Usage usage, String systemPrompt, String userPrompt);

    /** Same as {@link #complete}, but with LLM_FINAL_MODEL (if set). Only the final answer uses this. */
    String completeWithFinalModel(Usage usage, String systemPrompt, String userPrompt);
}
