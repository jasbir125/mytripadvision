package com.mytripadvision.dto;

import java.util.List;
import java.util.Map;

/**
 * status is "done" (final answer) or "needs_review" (a draft plan waits for the user).
 * agent is the agent that wrote the answer: guardrails, supervisor, itinerary or final.
 * notices are one-line warnings about services that were down or out of quota while the plan was made.
 */
public record TravelResponse(
        boolean success,
        String status,
        String agent,
        String threadId,
        String answer,
        String flightResults,
        String hotelResults,
        String itinerary,
        String budgetAnalysis,
        Map<String, Object> tripConstraints,
        int llmCalls,
        String requestId,
        List<String> plan,
        List<String> notices,
        Map<String, Object> tokenUsage) {
}
