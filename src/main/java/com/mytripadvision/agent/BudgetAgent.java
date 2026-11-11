package com.mytripadvision.agent;

import com.mytripadvision.client.LlmClient;
import com.mytripadvision.workflow.WorkflowState;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Estimates the trip cost and compares it with the user's budget. */
@Component
@RequiredArgsConstructor
@Slf4j
public class BudgetAgent {

    private static final String PROMPT = """
            Analyse the budget for this trip.

            Trip details:
            {constraints}

            Request:
            {query}

            Rules for this trip:
            {rules}

            Flights:
            {flights}

            Hotels:
            {hotels}

            Reply in markdown, under 200 words:
            - A small table: Category | Estimate  (flights, stay, food, local transport, activities)
            - The total, with the verdict from the rules above
            - 2-3 money-saving tips

            If real prices are missing, say the numbers are estimates.""";

    private final LlmClient llm;

    /** The rules depend on what the user gave. We decide that here, in code, so the model never has to guess. */
    static String rules(Map<String, Object> trip) {
        StringBuilder rules = new StringBuilder();
        if (!SupervisorAgent.hasValue(trip.get("days")) || !SupervisorAgent.hasValue(trip.get("travelers"))) {
            rules.append("- The number of days and/or travelers was not given. Never assume it: give prices per night, ")
                    .append("per day or per person instead of one trip total.\n");
        }
        if (!SupervisorAgent.hasOrigin(trip)) {
            rules.append("- The departure city was not given. For flights write \"depends on the departure city\" ")
                    .append("instead of a price, and leave flights out of any total.\n");
        }
        if (SupervisorAgent.hasValue(trip.get("budget"))) {
            rules.append("- Compare the total with the budget and give a verdict: within budget, or over by how much.");
        } else {
            rules.append("- No budget was given. Do not invent one. In place of a verdict write: ")
                    .append("\"No budget was given, so there is nothing to compare with.\"");
        }
        return rules.toString();
    }

    public String run(WorkflowState state) {
        log.info("[budget] started");
        String prompt = PROMPT
                .replace("{constraints}", state.getTripConstraints().isEmpty() ? "(not specified)" : state.getTripConstraints().toString())
                .replace("{rules}", rules(state.getTripConstraints()))
                .replace("{flights}", Texts.clip(state.getFlightResults(), 1200))
                .replace("{hotels}", Texts.clip(state.getHotelResults(), 1500))
                .replace("{query}", state.getUserQuery());
        String reply = llm.complete(state.getUsage(), "You are a careful travel budget analyst.", prompt);
        state.addLlmCalls(1);
        log.info("[budget] done");
        return reply;
    }
}
