package com.mytripadvision.agent;

import com.mytripadvision.client.LlmClient;
import com.mytripadvision.workflow.WorkflowState;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Writes the answer the user sees, with only the sections of the agents that ran. Uses LLM_FINAL_MODEL. */
@Component
@RequiredArgsConstructor
@Slf4j
public class FinalAgent {

    private static final String PROMPT = """
            Write the final travel plan for the user. Use ONLY the information below.

            USER REQUEST
            {query}

            TRIP DETAILS GIVEN BY THE USER
            {constraints}

            FLIGHTS
            {flights}

            HOTELS
            {hotels}

            WEATHER
            {weather}

            BUDGET ANALYSIS
            {budget}

            ITINERARY
            {itinerary}

            Agents that were run for this request: {plan}

            Write the answer in Markdown with these sections, in this order. Skip the section of an agent
            that was not run. "Trip Summary" and "Final Recommendations" are always included.

            ## Trip Summary
            2-3 sentences with the destination and whatever else the user gave (origin, days, budget, travel style).
            Leave out anything they did not give.

            ## Flight Information
            {flight_points}

            ## Hotel Suggestions
            A table with the columns Hotel | Area | Approx. price per night | Why it fits.
            Use only hotels named in the hotel text. If a price is not given, write "not listed".
            Pick 3-5 that match the request (for example budget hotels).

            ## Weather Information
            Current conditions and the forecast in one or two lines, then 3 short tips based on the weather
            (what to pack, best time of day for sightseeing, rain plans).

            ## Budget Analysis
            Keep the budget table from the budget text (category and estimate) with the total, then 2-3 ways
            to save money.

            ## Day-by-Day Itinerary
            Keep every day and the activities, times and places of the itinerary above. You may tidy the
            wording, but do not shorten it or drop days.

            ## Final Recommendations
            4-5 bullets: best way to book flights and hotels, local transport, money and payments,
            safety or visa and entry reminders, and one tip for this destination.

            Rules:
            {not_given_rule}
            - Use real names, places and numbers from the information above. Do not invent prices, hotels
              or flights. Write "not available" when something is missing.
            - Be practical and friendly. No opening line like "Sure!" and no closing offer like
              "let me know if you need more".
            - Keep currency units the same as in the request.
            """;

    /**
     * Used when only ONE agent ran (just the weather, just hotels...). A question about one thing gets a short,
     * focused answer, not a whole trip plan with a summary and recommendations about things nobody asked.
     */
    private static final String FOCUSED_PROMPT = """
            Answer the user's question using ONLY the information below.

            USER REQUEST
            {query}

            TRIP DETAILS GIVEN BY THE USER
            {constraints}

            INFORMATION ({agent})
            {info}

            Write a short, focused answer in Markdown:
            - Start with the heading "## {heading}", then the answer. {format}
            - Add 2-4 practical tips based on the information, if they help.
            - Do NOT write a "Trip Summary" or "Final Recommendations". Do not mention trip details the user did
              not give, and do not mention flights, hotels or anything else this answer is not about.

            Rules:
            - Use real names, places and numbers from the information. Do not invent anything. Write
              "not available" when something is missing.
            - Be practical and friendly. No opening line like "Sure!" and no closing offer like
              "let me know if you need more".
            - Keep currency units the same as in the request.
            """;

    private final LlmClient llm;

    /** What the flight section must contain when we know where the trip starts. */
    static final String FLIGHT_POINTS_WITH_ORIGIN = """
            - Likely departure and arrival airports.
            - Airlines that serve the route and the typical flight time.
            - An estimated fare range, clearly marked as an estimate. If the flight text starts with a
              "Note" that live data was unavailable, keep that note.
            - One line saying ticket prices may not be available from the live flight API.""";

    /** ...and when the user never said where they travel from: no invented city, flight time or fare. */
    static final String FLIGHT_POINTS_WITHOUT_ORIGIN = """
            - The arrival airport and the airlines that commonly fly there.
            - Departure city: write "not given", and say that the flight time and the fares depend on where the trip starts.
              Give no flight time and no fare.
            - If the flight text starts with a "Note" that live data was unavailable, keep that note.
            - One line saying ticket prices may not be available from the live flight API.""";

    /** The heading of a single-agent answer and how its body must look. */
    private record Section(String heading, String format) {
    }

    private static Section focusedSection(String agent) {
        return switch (agent) {
            case "weather" -> new Section("Weather Information",
                    "Give the current conditions and the forecast in one or two lines.");
            case "hotel" -> new Section("Hotel Suggestions",
                    "Use a table with the columns Hotel | Area | Approx. price per night | Why it fits. Use only hotels named in "
                            + "the information (3-5 of them); write \"not listed\" when a price is missing.");
            case "flight" -> new Section("Flight Information",
                    "Keep a leading \"Note\" if the information has one. Say that ticket prices may not be available.");
            case "budget" -> new Section("Budget Analysis",
                    "Keep the budget table and the verdict from the information.");
            default -> new Section("Itinerary",
                    "Keep every day and the activities, times and places. You may tidy the wording, but do not shorten it.");
        };
    }

    private static String focusedInfo(String agent, WorkflowState state) {
        return switch (agent) {
            case "weather" -> Texts.clip(state.getWeatherResults(), 1500);
            case "hotel" -> Texts.clip(state.getHotelResults(), 3500);
            case "flight" -> Texts.clip(state.getFlightResults(), 2000);
            case "budget" -> Texts.clip(state.getBudgetAnalysis(), 2000);
            default -> state.getItinerary();
        };
    }

    /** Names exactly the details the user left out (decided in code, not by the model). */
    static String notGivenRule(Map<String, Object> trip) {
        String missing = Texts.missingDetails(trip);
        if (missing.equals("none")) {
            return "- The user gave all the trip details above. Use them as given.";
        }
        String rule = "- The user did not give: " + missing + ". Write \"not specified\" for these and never state a value for them.";
        if (!SupervisorAgent.hasValue(trip.get("days"))) {
            rule += " The only exception: if the itinerary says it assumed 3 days, keep that wording.";
        }
        return rule;
    }

    public String run(WorkflowState state) {
        log.info("[final] started, building answer for plan {}", state.getPlan());
        String reply = llm.completeWithFinalModel(state.getUsage(), "You are a professional AI travel booking assistant.",
                state.getPlan().size() == 1 ? focusedPrompt(state) : fullPrompt(state));
        state.addLlmCalls(1);
        log.info("[final] done");
        return reply;
    }

    private String focusedPrompt(WorkflowState state) {
        String agent = state.getPlan().get(0);
        Section section = focusedSection(agent);
        return FOCUSED_PROMPT
                .replace("{agent}", agent)
                .replace("{heading}", section.heading())
                .replace("{format}", section.format())
                .replace("{info}", focusedInfo(agent, state))
                .replace("{constraints}", Texts.tripDetails(state.getTripConstraints()))
                .replace("{query}", state.getUserQuery());
    }

    private String fullPrompt(WorkflowState state) {
        Map<String, Object> trip = state.getTripConstraints();
        String itinerary = state.getItinerary().isEmpty() ? "Not requested" : state.getItinerary();
        return PROMPT
                .replace("{flights}", Texts.clip(state.getFlightResults(), 2000))
                .replace("{hotels}", Texts.clip(state.getHotelResults(), 3500))
                .replace("{weather}", Texts.clip(state.getWeatherResults(), 1500))
                .replace("{budget}", Texts.clip(state.getBudgetAnalysis(), 2000))
                .replace("{itinerary}", itinerary)
                .replace("{plan}", String.join(", ", state.getPlan()))
                .replace("{constraints}", Texts.tripDetails(trip))
                .replace("{not_given_rule}", notGivenRule(trip))
                .replace("{flight_points}", SupervisorAgent.hasOrigin(trip) ? FLIGHT_POINTS_WITH_ORIGIN : FLIGHT_POINTS_WITHOUT_ORIGIN)
                .replace("{query}", state.getUserQuery());
    }
}
