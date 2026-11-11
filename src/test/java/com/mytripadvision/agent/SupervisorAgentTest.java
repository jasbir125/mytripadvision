package com.mytripadvision.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mytripadvision.client.LlmClient;
import com.mytripadvision.workflow.WorkflowState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class SupervisorAgentTest {

    private final LlmClient llm = mock(LlmClient.class);
    private final SupervisorAgent supervisor = new SupervisorAgent(llm, JsonMapper.builder().build());

    private SupervisorAgent.Decision parse(String reply, Map<String, Object> known) {
        return supervisor.parseDecision(reply, "original", known);
    }

    @Test
    void jsonWrappedInMarkdown_isParsed_andAgentsKeepCanonicalOrder() {
        var decision = parse("""
                Sure! ```json
                {"agents": ["itinerary", "flight", "bogus", "hotel"], "clarification": null,
                 "query": "7 days in Tokyo", "trip_constraints": {"destination": "Tokyo", "days": 7},
                 "reasoning": "needs plan"}
                ```""", Map.of());

        assertThat(decision.plan()).containsExactly("flight", "hotel", "itinerary");
        assertThat(decision.query()).isEqualTo("7 days in Tokyo");
        assertThat(decision.constraints()).containsEntry("destination", "Tokyo").containsEntry("days", 7);
        assertThat(decision.clarification()).isEmpty();
        assertThat(decision.reasoning()).isEqualTo("needs plan");
    }

    @Test
    void clarification_leavesThePlanEmpty() {
        var decision = parse("{\"agents\": [\"flight\"], \"clarification\": \" Where to? \"}", Map.of());

        assertThat(decision.clarification()).isEqualTo("Where to?");
        assertThat(decision.plan()).isEmpty();
        assertThat(decision.query()).isEqualTo("original");
    }

    @Test
    void emptyAgentList_meansAllAgents() {
        assertThat(parse("{\"agents\": []}", Map.of("destination", "Rome")).plan())
                .containsExactlyElementsOf(SupervisorAgent.ALL_AGENTS);
    }

    @Test
    void newConstraintsOverrideOldOnes_butEmptyValuesDoNot() {
        Map<String, Object> known = new LinkedHashMap<>(Map.of("destination", "Paris", "days", 3));
        var decision = parse("""
                {"agents": ["hotel"], "trip_constraints": {"destination": "Rome", "days": null, "budget": "", "travelers": 0,
                 "origin": "Delhi"}}""", known);

        assertThat(decision.constraints()).containsEntry("destination", "Rome").containsEntry("days", 3)
                .containsEntry("origin", "Delhi").doesNotContainKeys("budget", "travelers");
    }

    // --- business rule: a trip request needs a destination ---

    @Test
    void noDestination_asksTheUser_insteadOfRunningTheAgents() {
        var decision = parse("""
                {"agents": ["flight", "hotel", "weather", "budget", "itinerary"], "clarification": null,
                 "trip_constraints": {"destination": null, "origin": null, "days": null}}""", Map.of());

        assertThat(decision.plan()).isEmpty();
        assertThat(decision.clarification()).isEqualTo(SupervisorAgent.ASK_DESTINATION);
    }

    @Test
    void aPlaceholderDestination_countsAsMissing() {
        for (String placeholder : List.of("Not specified", "unknown", "N/A", "  none ", "TBD")) {
            var decision = parse("{\"agents\": [\"hotel\"], \"trip_constraints\": {\"destination\": \"" + placeholder + "\"}}",
                    Map.of());

            assertThat(decision.clarification()).as(placeholder).isEqualTo(SupervisorAgent.ASK_DESTINATION);
            assertThat(decision.plan()).as(placeholder).isEmpty();
        }
    }

    @Test
    void aDestinationInTheMessage_letsTheAgentsRun() {
        var decision = parse("{\"agents\": [\"hotel\"], \"trip_constraints\": {\"destination\": \"Goa\"}}", Map.of());

        assertThat(decision.clarification()).isEmpty();
        assertThat(decision.plan()).containsExactly("hotel");
    }

    @Test
    void aDestinationKnownFromEarlierInTheChat_isEnough_forAFollowUp() {
        var decision = parse("{\"agents\": [\"weather\"], \"trip_constraints\": {\"destination\": null}}",
                Map.of("destination", "Jaipur"));

        assertThat(decision.clarification()).isEmpty();
        assertThat(decision.plan()).containsExactly("weather");
        assertThat(decision.constraints()).containsEntry("destination", "Jaipur");
    }

    @Test
    void aClarificationFromTheModel_isKeptAsItIs() {
        var decision = parse("{\"agents\": [], \"clarification\": \"I only help with travel.\"}", Map.of());

        assertThat(decision.clarification()).isEqualTo("I only help with travel.");
    }

    @Test
    void decide_promptTellsTheModelNotToAskAboutDetails_andHowToRouteCostAndSingleTopicQuestions() {
        when(llm.complete(any(), any(), any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(2);
            assertThat(prompt)
                    .contains("Never ask the user questions about trip details")
                    .contains("the application asks for a missing destination by itself")
                    .contains("A weather question never needs dates.")
                    .contains("needs only flight, hotel and budget")
                    .contains("needs only that agent")
                    .contains("fill \"destination\" with that city or country");
            return "{\"agents\": [\"weather\"], \"trip_constraints\": {\"destination\": \"Tokyo\"}}";
        });
        WorkflowState state = new WorkflowState();
        state.setUserQuery("What's the weather in Tokyo next week?");

        assertThat(supervisor.decide(state).plan()).containsExactly("weather");
    }

    @Test
    void replyWithoutJson_isRejected() {
        assertThatThrownBy(() -> parse("I cannot help", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void decide_fallsBackToAllAgents_whenTheLlmFails() {
        when(llm.complete(any(), any(), any())).thenThrow(new IllegalStateException("boom"));
        WorkflowState state = new WorkflowState();
        state.setUserQuery("hello");
        state.setTripConstraints(new LinkedHashMap<>(Map.of("destination", "Paris")));

        var decision = supervisor.decide(state);

        assertThat(decision.plan()).containsExactlyElementsOf(SupervisorAgent.ALL_AGENTS);
        assertThat(decision.query()).isEqualTo("hello");
        assertThat(decision.constraints()).containsEntry("destination", "Paris");
    }

    @Test
    void decide_usesOnlyTheLastTwoEarlierQuestions_inThePrompt() {
        when(llm.complete(any(), any(), any())).thenAnswer(inv -> {
            String prompt = inv.getArgument(2);
            assertThat(prompt).contains("- second", "- third").doesNotContain("- first");
            return "{\"agents\": [\"weather\"], \"trip_constraints\": {\"destination\": \"Rome\"}}";
        });
        WorkflowState state = new WorkflowState();
        state.setUserQuery("now");
        state.setPreviousQuestions(List.of("first", "second", "third"));

        assertThat(supervisor.decide(state).plan()).containsExactly("weather");
    }
}
