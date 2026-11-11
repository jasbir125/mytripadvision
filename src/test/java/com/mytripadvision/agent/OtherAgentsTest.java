package com.mytripadvision.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mytripadvision.client.LlmClient;
import com.mytripadvision.client.TavilyClient;
import com.mytripadvision.client.WeatherClient;
import com.mytripadvision.config.AppProperties;
import com.mytripadvision.exception.ExternalServiceException;
import com.mytripadvision.rag.KnowledgeBaseService;
import com.mytripadvision.rag.SearchResultsRag;
import com.mytripadvision.workflow.WorkflowState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OtherAgentsTest {

    private final LlmClient llm = mock(LlmClient.class);

    private WorkflowState state() {
        WorkflowState state = new WorkflowState();
        state.setUserQuery("Trip to Lisbon");
        state.setFlightResults("F".repeat(3000));
        state.setHotelResults("H".repeat(3500));
        state.setWeatherResults("sunny");
        state.setPlan(List.of("flight", "budget"));
        return state;
    }

    @Test
    void weather_asksTheLlmForOneCity_thenFetchesCurrentAndForecast() {
        WeatherClient weather = mock(WeatherClient.class);
        when(llm.complete(any(), any(), anyString())).thenReturn("  Lisbon \n");
        when(weather.current("Lisbon")).thenReturn("{\"city\":\"Lisbon\"}");
        when(weather.forecast("Lisbon")).thenReturn("{\"forecast\":[]}");
        WorkflowState state = state();

        String result = new WeatherAgent(llm, weather).run(state);

        assertThat(result).isEqualTo("Current Weather:\n{\"city\":\"Lisbon\"}\n\nForecast:\n{\"forecast\":[]}");
        assertThat(state.getLlmCalls()).isEqualTo(1);
    }

    @Test
    void weather_failureOfTheApi_propagates() {
        WeatherClient weather = mock(WeatherClient.class);
        when(llm.complete(any(), any(), anyString())).thenReturn("Lisbon");
        when(weather.current(anyString())).thenThrow(new ExternalServiceException("openweather", "HTTP 502"));

        assertThatThrownBy(() -> new WeatherAgent(llm, weather).run(state())).isInstanceOf(ExternalServiceException.class);
    }

    @Test
    void hotel_usesTheRagResult_andFallsBackToRawResultsWhenRagFails() {
        TavilyClient tavily = mock(TavilyClient.class);
        SearchResultsRag rag = mock(SearchResultsRag.class);
        when(tavily.search(eq("Best hotels for Trip to Lisbon")))
                .thenReturn(List.of(new TavilyClient.Document("Hotel A", "https://a", "nice rooms")));
        when(rag.retrieveRelevant(any(), anyString(), eq("hotel"))).thenReturn("TOP CHUNKS")
                .thenThrow(new IllegalStateException("db down"));
        HotelAgent agent = new HotelAgent(tavily, rag);
        WorkflowState state = state();

        assertThat(agent.run(state)).isEqualTo("TOP CHUNKS");
        assertThat(agent.run(state)).isEqualTo("[Hotel A](https://a)\nnice rooms");
        assertThat(state.getLlmCalls()).isZero();
    }

    @Test
    void budget_clipsItsInputs() {
        when(llm.complete(any(), any(), any())).thenReturn("TABLE");

        String result = new BudgetAgent(llm).run(state());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(any(), eq("You are a careful travel budget analyst."), prompt.capture());
        assertThat(result).isEqualTo("TABLE");
        assertThat(prompt.getValue()).contains("F".repeat(1200) + "\n...[truncated]").doesNotContain("F".repeat(1201))
                .contains("(not specified)");
    }

    @Test
    void itinerary_includesKnowledgeTips_andContinuesWithoutThemWhenTheLookupFails() {
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.search(anyString())).thenReturn("### Lisbon Guide - Transport\ntram 28")
                .thenThrow(new IllegalStateException("db down"));
        when(llm.complete(any(), any(), any())).thenReturn("PLAN");
        ItineraryAgent agent = new ItineraryAgent(llm, knowledge);

        agent.run(state());
        String withoutKnowledge = agent.run(state());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(any(), eq("You are an expert travel planner."), prompt.capture());
        assertThat(prompt.getAllValues().get(0)).contains("tram 28");
        assertThat(prompt.getAllValues().get(1)).doesNotContain("tram 28");
        assertThat(withoutKnowledge).isEqualTo("PLAN");
    }

    @Test
    void finalAnswer_listsThePlan_andSaysWhenTheItineraryWasNotRequested() {
        when(llm.completeWithFinalModel(any(), any(), any())).thenReturn("ANSWER");

        String result = new FinalAgent(llm).run(state());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).completeWithFinalModel(any(), eq("You are a professional AI travel booking assistant."),
                prompt.capture());
        assertThat(result).isEqualTo("ANSWER");
        assertThat(prompt.getValue()).contains("Agents that were run for this request: flight, budget")
                .contains("ITINERARY\nNot requested");
    }

    // --- the agents must not invent details the user never gave ---

    @Test
    void missingDetails_listsWhatTheUserDidNotGive() {
        assertThat(Texts.missingDetails(Map.of())).isEqualTo("number of days, number of travelers, budget, travel dates");
        assertThat(Texts.missingDetails(Map.of("destination", "Bangkok", "days", 0, "budget", "")))
                .isEqualTo("number of days, number of travelers, budget, travel dates");
        assertThat(Texts.missingDetails(Map.of("days", 4, "budget", 40000))).isEqualTo("number of travelers, travel dates");
        assertThat(Texts.missingDetails(Map.of("days", 4, "travelers", 2, "budget", 40000, "start_date", "2026-12-01")))
                .isEqualTo("none");
    }

    private String promptOf(WorkflowState state, java.util.function.Consumer<WorkflowState> agent, boolean finalModel) {
        agent.accept(state);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        if (finalModel) {
            verify(llm).completeWithFinalModel(any(), any(), prompt.capture());
        } else {
            verify(llm).complete(any(), any(), prompt.capture());
        }
        return prompt.getValue();
    }

    private WorkflowState withTrip(Map<String, Object> trip) {
        WorkflowState state = state();
        state.setTripConstraints(new LinkedHashMap<>(trip));
        return state;
    }

    @Test
    void budget_withNothingGiven_askForPerUnitPrices_andNoVerdict() {
        when(llm.complete(any(), any(), any())).thenReturn("TABLE");

        String prompt = promptOf(withTrip(Map.of("destination", "Bangkok")), s -> new BudgetAgent(llm).run(s), false);

        assertThat(prompt).contains("per night, per day or per person instead of one trip total")
                .contains("No budget was given. Do not invent one.")
                .contains("No budget was given, so there is nothing to compare with.")
                .doesNotContain("Compare the total with the budget");
    }

    @Test
    void budget_withEverythingGiven_asksForAVerdict_andHasNoAssumptionRules() {
        when(llm.complete(any(), any(), any())).thenReturn("TABLE");

        String prompt = promptOf(withTrip(Map.of("days", 4, "travelers", 2, "budget", 40000)),
                s -> new BudgetAgent(llm).run(s), false);

        assertThat(prompt).contains("Compare the total with the budget and give a verdict")
                .doesNotContain("Never assume it").doesNotContain("nothing to compare with");
    }

    @Test
    void budget_withoutADepartureCity_writesNoAirfare() {
        when(llm.complete(any(), any(), any())).thenReturn("TABLE");

        String prompt = promptOf(withTrip(Map.of("destination", "Bali", "days", 4, "travelers", 2)),
                s -> new BudgetAgent(llm).run(s), false);

        assertThat(prompt).contains("The departure city was not given")
                .contains("depends on the departure city").contains("leave flights out of any total");
    }

    @Test
    void budget_withADepartureCity_hasNoDepartureRule() {
        when(llm.complete(any(), any(), any())).thenReturn("TABLE");

        String prompt = promptOf(withTrip(Map.of("destination", "Bali", "origin", "Delhi")),
                s -> new BudgetAgent(llm).run(s), false);

        assertThat(prompt).doesNotContain("The departure city was not given");
    }

    // --- one-topic questions get a short answer, not a whole trip plan ---

    @Test
    void finalAnswer_forOneAgentOnly_isFocused_withNoSummaryOrRecommendations() {
        when(llm.completeWithFinalModel(any(), any(), any())).thenReturn("ANSWER");
        WorkflowState state = withTrip(Map.of("destination", "Tokyo"));
        state.setPlan(List.of("weather"));
        state.setWeatherResults("12 C, few clouds");

        String prompt = promptOf(state, s -> new FinalAgent(llm).run(s), true);

        assertThat(prompt).startsWith("Answer the user's question using ONLY the information below.")
                .contains("INFORMATION (weather)", "12 C, few clouds", "## Weather Information")
                .contains("Do NOT write a \"Trip Summary\" or \"Final Recommendations\"")
                .doesNotContain("Write the final travel plan").doesNotContain("FLIGHTS\n").doesNotContain("ITINERARY\n");
    }

    @Test
    void finalAnswer_forHotelsOnly_asksForATable() {
        when(llm.completeWithFinalModel(any(), any(), any())).thenReturn("ANSWER");
        WorkflowState state = withTrip(Map.of("destination", "Bangkok"));
        state.setPlan(List.of("hotel"));
        state.setHotelResults("Hotel A, Hotel B");

        String prompt = promptOf(state, s -> new FinalAgent(llm).run(s), true);

        assertThat(prompt).contains("## Hotel Suggestions", "Hotel | Area | Approx. price per night | Why it fits", "Hotel A, Hotel B");
    }

    @Test
    void finalAnswer_withoutAnOrigin_saysDepartureNotGiven_andGivesNoFlightTimeOrFare() {
        when(llm.completeWithFinalModel(any(), any(), any())).thenReturn("ANSWER");
        WorkflowState state = withTrip(Map.of("destination", "Bali"));
        state.setPlan(List.of("flight", "budget"));

        String prompt = promptOf(state, s -> new FinalAgent(llm).run(s), true);

        assertThat(prompt).contains(FinalAgent.FLIGHT_POINTS_WITHOUT_ORIGIN, "Departure city: write \"not given\"")
                .contains("Give no flight time and no fare.").doesNotContain("Likely departure and arrival airports");
    }

    @Test
    void finalAnswer_withAnOrigin_keepsTheNormalFlightSection() {
        when(llm.completeWithFinalModel(any(), any(), any())).thenReturn("ANSWER");
        WorkflowState state = withTrip(Map.of("destination", "Dubai", "origin", "Dhaka"));
        state.setPlan(List.of("flight", "budget"));

        String prompt = promptOf(state, s -> new FinalAgent(llm).run(s), true);

        assertThat(prompt).contains(FinalAgent.FLIGHT_POINTS_WITH_ORIGIN, "Likely departure and arrival airports")
                .doesNotContain("Departure city: write");
    }

    @Test
    void finalAnswer_forSeveralAgents_stillUsesTheFullPlanLayout() {
        when(llm.completeWithFinalModel(any(), any(), any())).thenReturn("ANSWER");
        WorkflowState state = withTrip(Map.of("destination", "Bali"));
        state.setPlan(List.of("flight", "hotel", "budget"));

        String prompt = promptOf(state, s -> new FinalAgent(llm).run(s), true);

        assertThat(prompt).startsWith("Write the final travel plan for the user.")
                .contains("## Trip Summary", "## Final Recommendations").doesNotContain("Answer the user's question");
    }

    @Test
    void itinerary_withDaysGiven_mustHaveExactlyThatManyDays_andNeverMentionsAssuming() {
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.search(anyString())).thenReturn("");
        when(llm.complete(any(), any(), any())).thenReturn("PLAN");

        String prompt = promptOf(withTrip(Map.of("destination", "Dubai", "days", 5)),
                s -> new ItineraryAgent(llm, knowledge).run(s), false);

        assertThat(prompt).contains("Plan exactly 5 days, no more and no fewer.")
                .doesNotContain("Assumed 3 days").doesNotContain("was not given");
    }

    @Test
    void itinerary_withoutDays_assumesThreeAndSaysSo() {
        KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
        when(knowledge.search(anyString())).thenReturn("");
        when(llm.complete(any(), any(), any())).thenReturn("PLAN");

        String prompt = promptOf(withTrip(Map.of("destination", "Goa", "budget", 50000)),
                s -> new ItineraryAgent(llm, knowledge).run(s), false);

        assertThat(prompt).contains("The number of days was not given. Plan 3 days")
                .contains("Assumed 3 days because the number of days was not given.")
                .doesNotContain("Plan exactly");
    }

    @Test
    void finalAnswer_namesOnlyTheDetailsThatAreReallyMissing() {
        when(llm.completeWithFinalModel(any(), any(), any())).thenReturn("ANSWER");

        String prompt = promptOf(withTrip(Map.of("destination", "Dubai", "days", 5)),
                s -> new FinalAgent(llm).run(s), true);

        assertThat(prompt).contains("destination=Dubai", "days=5")   // (map order is not fixed, so check each)
                .contains("The user did not give: number of travelers, budget, travel dates.")
                .doesNotContain("number of days").doesNotContain("assumed 3 days");
    }

    @Test
    void finalAnswer_withEverythingGiven_hasNoMissingRule() {
        when(llm.completeWithFinalModel(any(), any(), any())).thenReturn("ANSWER");

        String prompt = promptOf(withTrip(Map.of("days", 4, "travelers", 2, "budget", 40000, "start_date", "2026-12-01")),
                s -> new FinalAgent(llm).run(s), true);

        assertThat(prompt).contains("The user gave all the trip details above.").doesNotContain("The user did not give");
    }

    @Test
    void memoryWriter_runsOnlyOnEverySecondQuestion() {
        AppProperties props = new AppProperties(null, null, null, null, null,
                new AppProperties.Workflow(true, 2, 2, ""));
        MemoryWriter writer = new MemoryWriter(llm, props);
        when(llm.complete(any(), any(), anyString())).thenReturn(" new summary ");
        WorkflowState first = state();
        first.setQuestionNumber(1);
        first.setSummary("old");
        WorkflowState second = state();
        second.setQuestionNumber(2);

        assertThat(writer.run(first, "answer")).isEqualTo("old");
        assertThat(writer.run(second, "answer")).isEqualTo("new summary");
        assertThat(second.getLlmCalls()).isEqualTo(1);
    }
}
