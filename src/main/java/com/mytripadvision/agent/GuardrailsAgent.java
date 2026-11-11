package com.mytripadvision.agent;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** First check on every request. No LLM call, so no tokens. */
@Component
@Slf4j
public class GuardrailsAgent {

    private static final int MAX_LENGTH = 1000;

    // Phrases people use to try to override the assistant's instructions.
    private static final List<String> INJECTION_PHRASES = List.of(
            "ignore previous instructions", "ignore all previous", "ignore your instructions",
            "disregard your instructions", "reveal your system prompt", "show your system prompt");

    /** @return the reason to block the request, or "" when it is fine */
    public String check(String text) {
        String problem = findProblem(text);
        if (problem.isEmpty()) {
            log.info("[guardrails] PASS");
        } else {
            log.warn("[guardrails] BLOCK (refused before any LLM call)");
        }
        return problem;
    }

    private String findProblem(String text) {
        if (text == null || text.isBlank()) {
            return "Please type a travel question or request.";
        }
        if (text.length() > MAX_LENGTH) {
            return "Your message is too long. Please keep it under " + MAX_LENGTH + " characters.";
        }
        String lower = text.toLowerCase();
        for (String phrase : INJECTION_PHRASES) {
            if (lower.contains(phrase)) {
                return "I can only help with travel planning, and I can't change my instructions.";
            }
        }
        return "";
    }
}
