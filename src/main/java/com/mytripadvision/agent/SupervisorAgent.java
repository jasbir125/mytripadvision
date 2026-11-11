package com.mytripadvision.agent;

import com.mytripadvision.client.LlmClient;
import com.mytripadvision.workflow.WorkflowState;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Decides which agents the request needs, with ONE LLM call. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SupervisorAgent {

    public static final List<String> ALL_AGENTS = List.of("flight", "hotel", "weather", "budget", "itinerary");

    /** What the supervisor decided. {@code plan} is empty when a clarification is needed. */
    public record Decision(List<String> plan, String clarification, String query, Map<String, Object> constraints,
                           String reasoning) {
    }

    private static final String PROMPT = """
            You are the supervisor of a travel-planning system.
            Decide which specialist agents are needed for the user's latest message.

            Agents:
            - flight: flight options / airports / airlines
            - hotel: hotel or accommodation suggestions
            - weather: weather or forecast for the destination
            - budget: cost estimate and budget analysis (use it when a budget or cost matters)
            - itinerary: day-by-day plan and activities

            Rules:
            - Pick ONLY the agents the request needs. A full trip-planning request needs all five.
            - A question about what a trip will COST ("how much would...", "what does a trip to X cost", "how much should I budget") needs only flight, hotel and budget. Add weather or itinerary ONLY if the user also asks for the weather, a plan, a schedule or activities.
            - A follow-up that asks about only one thing (the weather, hotels, flights, airlines or the cost) needs only that agent, even when the trip is already planned.
            - Never ask the user questions about trip details. For every travel request, fill "agents" and "trip_constraints" with what the message says and leave unknown values null (the application asks for a missing destination by itself). A weather question never needs dates.
            - Use "clarification" ONLY when the request is not about travel (put a short polite reply) or asks for something illegal, harmful or unsafe (refuse politely). Then leave "agents" empty.
            - Use the summary, known trip details and earlier messages to resolve follow-ups ("there", "it", "cheaper"). If the destination is already known from them, use it and do NOT ask again. "query" must be a standalone, complete restatement of what the user wants now.
            - "trip_constraints": fill only what the user said or what is already known; use null for unknown values. Whenever the message names a place to visit, fill "destination" with that city or country.

            Summary of the chat so far:
            {summary}

            Known trip details:
            {constraints}

            Earlier user messages:
            {history}

            Latest user message:
            {message}

            Reply with JSON only, no markdown:
            {"agents": ["flight","hotel","weather","budget","itinerary"], "clarification": null, "query": "...",
             "trip_constraints": {"destination": null, "origin": null, "start_date": null, "days": null, "budget": null, "currency": null, "travelers": null, "preferences": null},
             "reasoning": "one short sentence"}
            """;

    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    /** What we answer when the user did not say where they want to go. */
    static final String ASK_DESTINATION = "Which destination would you like to visit? "
            + "Please tell me the city or country (and, if you can, where you travel from, for how many days and your budget).";

    /** Words the model sometimes writes instead of leaving the destination empty. */
    private static final Set<String> PLACEHOLDERS = Set.of(
            "unknown", "not specified", "unspecified", "none", "null", "n/a", "na", "tbd", "any", "anywhere");

    private final LlmClient llm;
    private final JsonMapper json;

    public Decision decide(WorkflowState state) {
        Map<String, Object> known = new LinkedHashMap<>(state.getTripConstraints());

        // only the last two earlier questions go into the prompt
        List<String> earlier = state.getPreviousQuestions();
        String history = "";
        for (String question : earlier.subList(Math.max(0, earlier.size() - 2), earlier.size())) {
            history += "- " + question + "\n";
        }
        String summary = state.getSummary();

        String prompt = PROMPT
                .replace("{summary}", summary == null || summary.isBlank() ? "(none)" : summary)
                .replace("{constraints}", known.isEmpty() ? "(none)" : known.toString())
                .replace("{history}", history.isEmpty() ? "(none)" : history.strip())
                .replace("{message}", state.getUserQuery());
        try {
            String reply = llm.complete(state.getUsage(), "You route travel requests. Output JSON only.", prompt);
            state.addLlmCalls(1);
            return parseDecision(reply, state.getUserQuery(), known);
        } catch (RuntimeException ex) {
            // If routing fails for any reason, run the full pipeline.
            log.warn("[supervisor] routing failed ({}); falling back to ALL agents", ex.getClass().getSimpleName());
            return new Decision(new ArrayList<>(ALL_AGENTS), "", state.getUserQuery(), known, "");
        }
    }

    /**
     * Reads the LLM reply. It may be wrapped in text or markdown, so we take everything from the first "{"
     * to the last "}". Example of the JSON:
     * <pre>{"agents": ["flight", "hotel"], "clarification": null, "query": "Hotels in Rome",
     *  "trip_constraints": {"destination": "Rome", "days": null}, "reasoning": "needs stay and travel"}</pre>
     */
    Decision parseDecision(String reply, String message, Map<String, Object> known) {
        Matcher match = JSON_OBJECT.matcher(reply == null ? "" : reply);
        if (!match.find()) {
            throw new IllegalArgumentException("no JSON object in the supervisor reply");
        }
        JsonNode decision = json.readTree(match.group());

        String clarification = text(decision, "clarification");
        String query = text(decision, "query").isEmpty() ? message : text(decision, "query");

        // keep the agents in their fixed order and drop unknown names; none listed means all of them
        List<String> plan = new ArrayList<>();
        if (clarification.isEmpty()) {
            for (String agent : ALL_AGENTS) {
                for (JsonNode asked : decision.path("agents")) {
                    if (agent.equals(asked.asString(""))) {
                        plan.add(agent);
                    }
                }
            }
            if (plan.isEmpty()) {
                plan.addAll(ALL_AGENTS);
            }
        }

        // new values from this message replace old ones; empty values never erase what we knew
        Map<String, Object> constraints = new LinkedHashMap<>(known);
        if (decision.path("trip_constraints").isObject()) {
            Map<String, Object> fresh = json.convertValue(decision.path("trip_constraints"),
                    new TypeReference<LinkedHashMap<String, Object>>() { });
            fresh.forEach((key, value) -> {
                if (hasValue(value)) {
                    constraints.put(key, value);
                }
            });
        }

        // Business rule: every agent needs a destination. If the model did not ask for one, we ask here,
        // so the answer never depends on the model following its prompt (it once invented "New York to London").
        if (clarification.isEmpty() && !hasDestination(constraints)) {
            log.info("[supervisor] no destination in this message or earlier in the chat, asking the user");
            return new Decision(new ArrayList<>(), ASK_DESTINATION, query, constraints, text(decision, "reasoning"));
        }
        return new Decision(plan, clarification, query, constraints, text(decision, "reasoning"));
    }

    /** True when the trip details hold a real destination (not empty, and not a placeholder like "unknown"). */
    static boolean hasDestination(Map<String, Object> constraints) {
        return hasPlace(constraints, "destination");
    }

    /** True when the user said where they travel from. */
    static boolean hasOrigin(Map<String, Object> constraints) {
        return hasPlace(constraints, "origin");
    }

    private static boolean hasPlace(Map<String, Object> constraints, String key) {
        Object place = constraints.get(key);
        if (!hasValue(place)) {
            return false;
        }
        return !PLACEHOLDERS.contains(place.toString().strip().toLowerCase());
    }

    private static String text(JsonNode node, String field) {
        return node.path(field).isNull() ? "" : node.path(field).asString("").strip();
    }

    /** null, "", 0, false and empty lists/maps count as "no value" (same as the Python app). */
    static boolean hasValue(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String s) {
            return !s.isEmpty();
        }
        if (value instanceof Number n) {
            return n.doubleValue() != 0;
        }
        if (value instanceof Boolean b) {
            return b;
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return true;
    }
}
