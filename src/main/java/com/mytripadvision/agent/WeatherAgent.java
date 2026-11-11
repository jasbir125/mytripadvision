package com.mytripadvision.agent;

import com.mytripadvision.client.LlmClient;
import com.mytripadvision.client.WeatherClient;
import com.mytripadvision.workflow.WorkflowState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Current weather and forecast for the destination city. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WeatherAgent {

    private static final String PROMPT = """

                Extract the destination as ONE city name, because weather is looked up by city.
                If the request names only a country, return its most popular tourist city.
                If the latest change request names a new place, use that place.

                Query:
                {query}

                Return only the city name.
                """;

    private final LlmClient llm;
    private final WeatherClient weather;

    public String run(WorkflowState state) {
        log.info("[weather] started");
        // weather is looked up by city, so first ask the LLM which city the request means
        String city = Agents.ask(llm, state, null, PROMPT.replace("{query}", state.getUserQuery())).strip();
        log.info("[weather] destination city: '{}'", city);

        String result = "Current Weather:\n" + weather.current(city) + "\n\nForecast:\n" + weather.forecast(city);
        log.info("[weather] done (current + forecast fetched)");
        return result;
    }
}
