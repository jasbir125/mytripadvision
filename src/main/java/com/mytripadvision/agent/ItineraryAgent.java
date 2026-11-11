package com.mytripadvision.agent;

import com.mytripadvision.client.LlmClient;
import com.mytripadvision.rag.KnowledgeBaseService;
import com.mytripadvision.workflow.WorkflowState;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Builds the day-by-day plan from what the other agents found, plus tips from our own knowledge base. */
@Component
@RequiredArgsConstructor
@Slf4j
public class ItineraryAgent {

    private static final String PROMPT = """

            Create a complete travel itinerary.

            User Query:
            {query}

            Trip details given by the user: {constraints}
            {days_rule}
            Do not invent a budget or a number of travelers.

            Flight Results:
            {flights}

            Hotel Results:
            {hotels}

            Weather Results:
            {weather}

            Budget Analysis:
            {budget}

            Travel Knowledge (our own guides - use these tips about timing, transport,
            neighbourhoods and practical advice when they are relevant):
            {knowledge}

            Make the itinerary practical, budget-aware, and easy to follow.
            """;

    private final LlmClient llm;
    private final KnowledgeBaseService knowledge;

    public String run(WorkflowState state) {
        log.info("[itinerary] started");
        String prompt = PROMPT
                .replace("{constraints}", state.getTripConstraints().isEmpty() ? "(none)" : state.getTripConstraints().toString())
                .replace("{days_rule}", daysRule(state.getTripConstraints()))
                .replace("{flights}", Texts.clip(state.getFlightResults(), 2000))
                .replace("{hotels}", Texts.clip(state.getHotelResults(), 3000))
                .replace("{weather}", Texts.clip(state.getWeatherResults(), 2000))
                .replace("{budget}", Texts.clip(state.getBudgetAnalysis(), 1200))
                .replace("{knowledge}", travelKnowledge(state.getUserQuery()))
                .replace("{query}", state.getUserQuery());
        String reply = llm.complete(state.getUsage(), "You are an expert travel planner.", prompt);
        state.addLlmCalls(1);
        log.info("[itinerary] done, {} chars", reply.length());
        return reply;
    }

    /**
     * How long the itinerary must be. We decide it here, in code: a conditional sentence in the prompt
     * ("if the days are missing...") was followed even when the user had given the days.
     */
    static String daysRule(Map<String, Object> trip) {
        Object days = trip.get("days");
        if (SupervisorAgent.hasValue(days)) {
            String text = days instanceof Number number ? String.valueOf(number.intValue()) : days.toString();
            return "Plan exactly " + text + " days, no more and no fewer.";
        }
        return "The number of days was not given. Plan 3 days and start with the line "
                + "\"Assumed 3 days because the number of days was not given.\"";
    }

    /** The knowledge base is a bonus: if the lookup fails the plan is written without it. */
    String travelKnowledge(String query) {
        try {
            return knowledge.search(query);
        } catch (RuntimeException ex) {
            log.warn("[itinerary] knowledge lookup failed ({}); continuing without it", ex.getClass().getSimpleName());
            return "";
        }
    }
}
