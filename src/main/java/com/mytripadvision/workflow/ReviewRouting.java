package com.mytripadvision.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * What runs again after the user asks for changes. Only the data agents the change request points to re-run;
 * words that change the place also re-run hotels and weather, because their results belong to a place.
 */
public final class ReviewRouting {

    /** Agents that gather data and do not depend on each other, so they run in parallel. */
    public static final List<String> PARALLEL_AGENTS = List.of("flight", "hotel", "weather");

    private static final List<String> PLACE_CHANGE_WORDS =
            List.of("beach", "island", "mountain", "instead of", "switch to", "move to", "different city");
    private static final List<String> BUDGET_WORDS =
            List.of("budget", "cost", "price", "expensive", "cheaper", "cheap");
    private static final Map<String, List<String>> REVISION_KEYWORDS = new LinkedHashMap<>();

    static {
        REVISION_KEYWORDS.put("flight", List.of("flight", "airline", "airport", "fly"));
        List<String> hotel = new ArrayList<>(List.of("hotel", "stay", "accommodation", "resort", "hostel"));
        hotel.addAll(PLACE_CHANGE_WORDS);
        REVISION_KEYWORDS.put("hotel", hotel);
        List<String> weather = new ArrayList<>(List.of("weather", "rain", "season"));
        weather.addAll(PLACE_CHANGE_WORDS);
        REVISION_KEYWORDS.put("weather", weather);
    }

    public enum Next { FINAL, DATA_AGENTS, BUDGET, ITINERARY }

    public record Route(Next next, List<String> dataAgents) {
    }

    private ReviewRouting() {
    }

    /** Approved (no feedback) goes to the final answer; otherwise the relevant agents run again. */
    public static Route afterReview(String reviewFeedback, List<String> plan) {
        String feedback = reviewFeedback == null ? "" : reviewFeedback.toLowerCase();
        if (feedback.isEmpty()) {
            return new Route(Next.FINAL, List.of());
        }
        List<String> targets = new ArrayList<>();
        REVISION_KEYWORDS.forEach((agent, words) -> {
            if (words.stream().anyMatch(feedback::contains)) {
                targets.add(agent);
            }
        });
        if (!targets.isEmpty()) {
            return new Route(Next.DATA_AGENTS, targets);
        }
        boolean asksAboutMoney = BUDGET_WORDS.stream().anyMatch(feedback::contains);
        return new Route(asksAboutMoney && plan.contains("budget") ? Next.BUDGET : Next.ITINERARY, List.of());
    }
}
