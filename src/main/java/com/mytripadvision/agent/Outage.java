package com.mytripadvision.agent;

import com.mytripadvision.exception.ExternalServiceException;
import java.util.List;
import java.util.Map;

/** One-line wording for the user when a service is down or its usage limit is used up. */
public final class Outage {

    private static final Map<String, String> LABELS = Map.of(
            "llm", "AI model",
            "aviationstack", "Flight data (AviationStack)",
            "tavily", "Hotel search (Tavily)",
            "openweather", "Weather service (OpenWeather)");

    /** Words in a scrubbed failure reason that mean "quota used up" rather than "down". */
    private static final List<String> QUOTA_HINTS =
            List.of("429", "432", "usage_limit", "quota", "throttl", "insufficient");

    private Outage() {
    }

    /** For example "AI model usage limit reached" or "Hotel search (Tavily) unavailable". */
    public static String reason(RuntimeException ex) {
        if (!(ex instanceof ExternalServiceException failure)) {
            return "Unexpected error";
        }
        String label = LABELS.getOrDefault(failure.getService(), failure.getService());
        return label + (quotaReached(failure.getMessage()) ? " usage limit reached" : " unavailable");
    }

    static boolean quotaReached(String reason) {
        String lower = reason == null ? "" : reason.toLowerCase();
        for (String hint : QUOTA_HINTS) {
            if (lower.contains(hint)) {
                return true;
            }
        }
        return false;
    }
}
