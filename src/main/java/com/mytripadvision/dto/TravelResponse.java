package com.mytripadvision.dto;

import java.util.List;
import java.util.Map;

/** status is "done" (final answer) or "needs_review" (a draft plan waits for the user). */
public record TravelResponse(
        boolean success,
        String status,
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
        Map<String, Object> tokenUsage) {
}
