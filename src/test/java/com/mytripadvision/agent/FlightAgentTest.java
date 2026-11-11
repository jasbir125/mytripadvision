package com.mytripadvision.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.mytripadvision.client.FlightClient;
import com.mytripadvision.client.LlmClient;
import com.mytripadvision.exception.ExternalServiceException;
import com.mytripadvision.workflow.WorkflowState;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

class FlightAgentTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final FlightClient flights = mock(FlightClient.class);
    private final FlightAgent agent = new FlightAgent(llm, flights, JsonMapper.builder().build());
    private final WorkflowState state = new WorkflowState();

    FlightAgentTest() {
        state.setUserQuery("Delhi to Bangkok");
    }

    @Test
    void parseAirlines_readsAJsonArrayFromChattyReplies() {
        assertThat(agent.parseAirlines("Here you go: [\"Thai Airways\", \" Emirates \"] hope it helps"))
                .containsExactly("Thai Airways", "Emirates");
    }

    @Test
    void parseAirlines_keepsAtMostThree_dropsBlankAndNonStrings() {
        assertThat(agent.parseAirlines("[\"A\", 5, \"\", \"B\", null, \"C\", \"D\"]")).containsExactly("A", "B", "C");
    }

    @Test
    void parseAirlines_returnsEmpty_forNoArrayOrBrokenJson() {
        assertThat(agent.parseAirlines("no list here")).isEmpty();
        assertThat(agent.parseAirlines("[Thai Airways, Emirates]")).isEmpty();
        assertThat(agent.parseAirlines(null)).isEmpty();
    }

    @Test
    void liveData_isTruncatedPerAirline_andUsedInTheSecondCall() {
        when(llm.complete(any(), any(), any())).thenReturn("[\"Thai Airways\", \"Emirates\"]", "GUIDANCE");
        when(flights.flightsForAirline(eq("Thai Airways"), anyInt())).thenReturn("T".repeat(2500));
        when(flights.flightsForAirline(eq("Emirates"), anyInt())).thenReturn("[{\"flight_number\":\"EK1\"}]");

        String result = agent.run(state);

        assertThat(result).isEqualTo("GUIDANCE");
        assertThat(state.getLlmCalls()).isEqualTo(2);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(any(), any(), prompt.capture());
        assertThat(prompt.getAllValues().get(1)).contains("Thai Airways:\n" + "T".repeat(2000) + "\n\nEmirates:")
                .doesNotContain("T".repeat(2001)).contains(FlightAgent.LIVE_DATA_NOTICE);
    }

    @Test
    void withAnOrigin_theRouteAndTheFullGuidanceListAreUsed() {
        when(llm.complete(any(), any(), any())).thenReturn("[\"Emirates\"]", "GUIDANCE");
        when(flights.flightsForAirline(anyString(), anyInt())).thenReturn("[]");
        state.setTripConstraints(new java.util.LinkedHashMap<>(java.util.Map.of("origin", "Dhaka", "destination", "Dubai")));

        agent.run(state);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(any(), any(), prompt.capture());
        assertThat(prompt.getAllValues().get(0)).contains("fly between Dhaka and Dubai");
        assertThat(prompt.getAllValues().get(1)).contains(FlightAgent.TASKS_WITH_ORIGIN, "Likely departure airport")
                .contains("Estimated airfare range").doesNotContain("Departure city not given");
    }

    @Test
    void withoutAnOrigin_theModelMustNotInventADepartureCity_orAnAirfare() {
        when(llm.complete(any(), any(), any())).thenReturn("[\"Garuda Indonesia\"]", "GUIDANCE");
        when(flights.flightsForAirline(anyString(), anyInt())).thenReturn("[]");
        state.setTripConstraints(new java.util.LinkedHashMap<>(java.util.Map.of("destination", "Bali")));

        agent.run(state);

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(any(), any(), prompt.capture());
        assertThat(prompt.getAllValues().get(0)).contains("fly to Bali from major international hubs");
        assertThat(prompt.getAllValues().get(1)).contains(FlightAgent.TASKS_WITHOUT_ORIGIN)
                .contains("Do not guess a departure city or airport", "Departure city not given")
                .contains("Do not give a flight duration or an airfare number")
                .doesNotContain("Likely departure airport").doesNotContain("Estimated airfare range");
    }

    @Test
    void anOriginThatIsAPlaceholder_countsAsNotGiven() {
        assertThat(FlightAgent.routeText(java.util.Map.of("destination", "Bali", "origin", "Not specified")))
                .isEqualTo("to Bali from major international hubs");
    }

    @Test
    void noLiveData_prependsTheBanner_andTellsTheLlmNumbersAreEstimates() {
        when(llm.complete(any(), any(), any())).thenReturn("[\"Thai Airways\"]", "GUIDANCE");
        when(flights.flightsForAirline(anyString(), anyInt()))
                .thenThrow(new ExternalServiceException("aviationstack", "function_access_restricted"));

        String result = agent.run(state);

        assertThat(result).isEqualTo(FlightAgent.NO_LIVE_DATA_BANNER + "GUIDANCE");
        assertThat(result).startsWith("> **Note:** Live flight data could not be fetched");
        assertThat(state.noticeLines()).containsExactly("Flight data (AviationStack) unavailable: flight details are estimates.");
        assertThat(state.getLlmCalls()).isEqualTo(2);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(any(), any(), prompt.capture());
        assertThat(prompt.getAllValues().get(1)).contains(FlightAgent.NO_LIVE_DATA_NOTICE)
                .contains("Not available (live data could not be fetched).");
    }

    @Test
    void noAirlinesSuggested_skipsTheLookup_andFallsBackToEstimates() {
        when(llm.complete(any(), any(), any())).thenReturn("I do not know", "GUIDANCE");

        String result = agent.run(state);

        assertThat(result).startsWith(FlightAgent.NO_LIVE_DATA_BANNER);
        assertThat(state.noticeLines()).containsExactly("Live flight data not found: flight details are estimates.");
        verifyNoInteractions(flights);
    }

    @Test
    void quotaExhausted_isNamedInTheNotice_evenWhenAnotherAirlineJustFailed() {
        when(llm.complete(any(), any(), any())).thenReturn("[\"Thai Airways\", \"Emirates\"]", "GUIDANCE");
        when(flights.flightsForAirline(eq("Thai Airways"), anyInt()))
                .thenThrow(new ExternalServiceException("aviationstack", "function_access_restricted"));
        when(flights.flightsForAirline(eq("Emirates"), anyInt()))
                .thenThrow(new ExternalServiceException("aviationstack", "usage_limit_reached"));

        agent.run(state);

        assertThat(state.noticeLines()).containsExactly("Flight data (AviationStack) usage limit reached: flight details are estimates.");
    }

    @Test
    void anyFailure_returnsTheUnavailableMessage() {
        when(llm.complete(any(), any(), any())).thenThrow(new ExternalServiceException("llm", "HTTP 500"));

        String result = agent.run(state);

        assertThat(result).isEqualTo("Flight information unavailable: llm: HTTP 500");
        assertThat(state.noticeLines()).containsExactly(
                "AI model unavailable: the flight agent could not run, so there is no flight guidance.");
        assertThat(state.getLlmCalls()).isZero();
    }
}
