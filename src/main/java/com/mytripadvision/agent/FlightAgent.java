package com.mytripadvision.agent;

import com.mytripadvision.client.FlightClient;
import com.mytripadvision.client.LlmClient;
import com.mytripadvision.exception.ExternalServiceException;
import com.mytripadvision.workflow.Parallel;
import com.mytripadvision.workflow.WorkflowState;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Flight guidance in 3 steps: (1) the LLM names likely airlines, (2) we fetch each airline's live flights
 * from AviationStack in parallel, (3) the LLM writes the guidance. Without live data the answer starts
 * with a note and says the numbers are estimates.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FlightAgent {

    private static final int MAX_AIRLINES = 3;
    private static final int FLIGHTS_PER_AIRLINE = 30;
    private static final int MAX_CHARS_PER_AIRLINE = 2000;
    private static final String SYSTEM = "You are an expert travel flight planner.";

    private static final String PICK_AIRLINES_PROMPT = """

            Trip request: {query}
            Known trip details: {constraints}

            List up to {count} airlines that most likely fly {route} (direct or with one stop).
            Use the airline's full name, for example "Thai Airways" or "Emirates".

            Return ONLY a JSON array of airline names, nothing else.
            """;

    private static final String GUIDANCE_PROMPT = """

            You are a travel flight expert.

            User Query:
            {query}

            Trip details given by the user: {constraints}

            Live flights found for likely airlines (AviationStack):
            {flight_data}

            {data_notice}

            Generate:

            {tasks}

            Return concise travel guidance.
            """;

    /** The guidance when we know where the trip starts. */
    static final String TASKS_WITH_ORIGIN = """
            1. Likely departure airport
            2. Likely arrival airport
            3. Airlines serving this route
            4. Typical flight duration
            5. Estimated airfare range
            6. Peak season pricing warning
            7. Booking advice""";

    /** The guidance when the user never said where they travel from: the model must not invent a city. */
    static final String TASKS_WITHOUT_ORIGIN = """
            The user did NOT say where they travel from. Do not guess a departure city or airport.
            1. Departure: write "Departure city not given" and say that flight time and fares depend on it
            2. Likely arrival airport
            3. Airlines that commonly fly to the destination
            4. Peak season pricing warning
            5. Booking advice
            Do not give a flight duration or an airfare number."""; 

    static final String LIVE_DATA_NOTICE =
            "The flights above are live samples of what each airline is operating right now. "
                    + "Use them to confirm airlines and airports, but they are not a full timetable.";
    static final String NO_LIVE_DATA_NOTICE =
            "Live flight data is unavailable, so use your general knowledge. "
                    + "State clearly that airlines, durations and fares are estimates, not live data.";
    /** The message the user sees when the live lookups failed: on the page as a notice, and in the answer as a note. */
    public static final String NO_LIVE_DATA_TEXT =
            "Live flight data could not be fetched; please check airline sites for current options. "
                    + "Ticket prices may not be available from the live flight API.";
    /** The yellow line above the answer on the page. */
    public static final String NO_LIVE_DATA_LINE = "Note: " + NO_LIVE_DATA_TEXT;
    /** The same note inside the flight text, as Markdown. */
    public static final String NO_LIVE_DATA_BANNER = "> **Note:** " + NO_LIVE_DATA_TEXT + "\n\n";

    private static final Pattern JSON_ARRAY = Pattern.compile("\\[.*?]", Pattern.DOTALL);

    private final LlmClient llm;
    private final FlightClient flights;
    private final JsonMapper json;

    public String run(WorkflowState state) {
        log.info("[flight] started");
        try {
            List<String> airlines = pickAirlines(state);
            List<String> failures = Collections.synchronizedList(new ArrayList<>());
            Map<String, String> liveFlights = fetchFlights(airlines, failures);
            // The banner and the notice are only for a real failure: every lookup threw (quota used up, API down).
            boolean lookupFailed = liveFlights.isEmpty() && !failures.isEmpty();
            if (lookupFailed) {
                log.warn("[flight] live data failed for all {} airline(s) ({}) -> banner and notice added",
                        airlines.size(), failureReason(failures));
                state.addNotice("flight", NO_LIVE_DATA_LINE);
            } else if (liveFlights.isEmpty()) {
                log.info("[flight] no airline to look up, answering from general knowledge (no failure, so no banner)");
            }
            String guidance = buildGuidance(state, liveFlights);
            log.info("[flight] done, live data for {} airline(s)", liveFlights.size());
            return lookupFailed ? NO_LIVE_DATA_BANNER + guidance : guidance;
        } catch (RuntimeException ex) {
            log.warn("[flight] failed: {}", ex.getClass().getSimpleName());
            state.addNotice("flight", Outage.reason(ex) + ": the flight agent could not run, so there is no flight guidance.");
            return "Flight information unavailable: " + ex.getMessage();
        }
    }

    /** Why the lookups failed, for the log; a quota problem is named before an outage. */
    static String failureReason(List<String> failures) {
        String reason = failures.get(0);
        for (String failure : failures) {
            if (failure.contains("usage limit")) {
                reason = failure;
            }
        }
        return reason;
    }

    /** Step 1: LLM call that returns airline names. */
    private List<String> pickAirlines(WorkflowState state) {
        Map<String, Object> trip = state.getTripConstraints();
        String prompt = PICK_AIRLINES_PROMPT
                .replace("{query}", state.getUserQuery())
                .replace("{constraints}", Texts.tripDetails(trip))
                .replace("{route}", routeText(trip))
                .replace("{count}", String.valueOf(MAX_AIRLINES));
        List<String> airlines = parseAirlines(Agents.ask(llm, state, SYSTEM, prompt));
        log.info("[flight] likely airlines: {}", airlines);
        return airlines;
    }

    /** Step 2: live flights per airline, all at once. Airlines that fail are left out; why goes into failures. */
    private Map<String, String> fetchFlights(List<String> airlines, List<String> failures) {
        Map<String, Supplier<String>> tasks = new LinkedHashMap<>();
        for (String airline : airlines) {
            tasks.put(airline, () -> fetchOneAirline(airline, failures));
        }
        Map<String, String> found = new LinkedHashMap<>();
        Parallel.run(tasks).forEach((airline, data) -> {
            if (!data.isEmpty()) {
                found.put(airline, data);
            }
        });
        return found;
    }

    private String fetchOneAirline(String airline, List<String> failures) {
        try {
            return flights.flightsForAirline(airline, FLIGHTS_PER_AIRLINE);
        } catch (ExternalServiceException ex) {
            log.warn("[flight] {}: request failed: {}", airline, ex.getMessage());
            failures.add(Outage.reason(ex));
            return "";
        }
    }

    /** Step 3: LLM call that turns the flights (or the lack of them) into guidance. */
    private String buildGuidance(WorkflowState state, Map<String, String> liveFlights) {
        if (liveFlights.isEmpty()) {
            log.warn("[flight] no live flight data, answering from general knowledge");
        } else {
            log.info("[flight] live flights fetched for: {}", liveFlights.keySet());
        }
        Map<String, Object> trip = state.getTripConstraints();
        String prompt = GUIDANCE_PROMPT
                .replace("{query}", state.getUserQuery())
                .replace("{constraints}", Texts.tripDetails(trip))
                .replace("{tasks}", SupervisorAgent.hasOrigin(trip) ? TASKS_WITH_ORIGIN : TASKS_WITHOUT_ORIGIN)
                .replace("{flight_data}", flightDataText(liveFlights))
                .replace("{data_notice}", liveFlights.isEmpty() ? NO_LIVE_DATA_NOTICE : LIVE_DATA_NOTICE);
        return Agents.ask(llm, state, SYSTEM, prompt);
    }

    /** The live flights as prompt text: one block per airline, each cut to a fixed size. */
    private static String flightDataText(Map<String, String> liveFlights) {
        if (liveFlights.isEmpty()) {
            return "Not available (live data could not be fetched).";
        }
        List<String> blocks = new ArrayList<>();
        liveFlights.forEach((airline, data) ->
                blocks.add(airline + ":\n" + data.substring(0, Math.min(MAX_CHARS_PER_AIRLINE, data.length()))));
        return String.join("\n\n", blocks);
    }

    /** "between Dhaka and Dubai", or just "to Dubai" when the user did not say where they start. */
    static String routeText(Map<String, Object> trip) {
        String destination = String.valueOf(trip.getOrDefault("destination", "the destination"));
        return SupervisorAgent.hasOrigin(trip)
                ? "between " + trip.get("origin") + " and " + destination
                : "to " + destination + " from major international hubs";
    }

    /** Takes the first JSON array out of the reply, e.g. {@code Sure: ["Thai Airways", "Emirates"]}. */
    List<String> parseAirlines(String reply) {
        Matcher match = JSON_ARRAY.matcher(reply == null ? "" : reply);
        if (!match.find()) {
            return List.of();
        }
        JsonNode names;
        try {
            names = json.readTree(match.group());
        } catch (RuntimeException notJson) {
            return List.of();
        }
        List<String> airlines = new ArrayList<>();
        for (JsonNode name : names) {
            if (name.isString() && !name.asString().isBlank() && airlines.size() < MAX_AIRLINES) {
                airlines.add(name.asString().strip());
            }
        }
        return airlines;
    }
}
