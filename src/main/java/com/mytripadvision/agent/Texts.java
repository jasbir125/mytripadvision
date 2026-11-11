package com.mytripadvision.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Small text helpers the agents share when they build prompts. */
public final class Texts {

    private Texts() {
    }

    /**
     * The important trip details the user did NOT give, as a short text for a prompt, for example
     * "number of days, budget". Returns "none" when they were all given. The agents are told not to make these up.
     */
    public static String missingDetails(Map<String, Object> constraints) {
        Map<String, String> important = Map.of("days", "number of days", "travelers", "number of travelers",
                "budget", "budget", "start_date", "travel dates");
        List<String> missing = new ArrayList<>();
        // fixed order so the prompt is always the same
        for (String key : List.of("days", "travelers", "budget", "start_date")) {
            if (!SupervisorAgent.hasValue(constraints.get(key))) {
                missing.add(important.get(key));
            }
        }
        return missing.isEmpty() ? "none" : String.join(", ", missing);
    }

    /** The known trip details for a prompt, or "(none)" when nothing is known yet. */
    public static String tripDetails(Map<String, Object> constraints) {
        return tripDetails(constraints, "(none)");
    }

    /** Same as {@link #tripDetails(Map)}, with the text to use when nothing is known. */
    public static String tripDetails(Map<String, Object> constraints, String whenEmpty) {
        return constraints.isEmpty() ? whenEmpty : constraints.toString();
    }

    /** The text itself, or "(none)" when it is missing or blank. */
    public static String orNone(String text) {
        return text == null || text.isBlank() ? "(none)" : text;
    }

    /** Trims large tool output so prompts stay within the LLM token limits. */
    public static String clip(String value, int limit) {
        String text = value == null ? "" : value;
        return text.length() <= limit ? text : text.substring(0, limit) + "\n...[truncated]";
    }
}
