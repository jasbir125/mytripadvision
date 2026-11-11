package com.mytripadvision.workflow;

import com.mytripadvision.agent.BudgetAgent;
import com.mytripadvision.agent.FinalAgent;
import com.mytripadvision.agent.FlightAgent;
import com.mytripadvision.agent.GuardrailsAgent;
import com.mytripadvision.agent.HotelAgent;
import com.mytripadvision.agent.ItineraryAgent;
import com.mytripadvision.agent.MemoryWriter;
import com.mytripadvision.agent.SupervisorAgent;
import com.mytripadvision.agent.WeatherAgent;
import com.mytripadvision.config.AppProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The flow of one question, in plain Java:
 * guardrails, supervisor, flight/hotel/weather in parallel, budget, itinerary, human review, final answer, memory.
 * The state is changed in place. At the review step the run stops with NEEDS_REVIEW; the caller saves the
 * state, and {@link #resume} continues it in a later request.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TravelWorkflow {

    private final GuardrailsAgent guardrails;
    private final SupervisorAgent supervisor;
    private final FlightAgent flight;
    private final HotelAgent hotel;
    private final WeatherAgent weather;
    private final BudgetAgent budget;
    private final ItineraryAgent itinerary;
    private final FinalAgent finalAgent;
    private final MemoryWriter memoryWriter;
    private final AppProperties props;

    /** Runs a new question from the start. */
    public WorkflowOutcome start(WorkflowState state) {
        String problem = guardrails.check(state.getUserQuery());
        if (!problem.isEmpty()) {
            state.setClarification(problem);
            state.setPlan(new ArrayList<>());
            return done(problem);
        }

        SupervisorAgent.Decision decision = supervisor.decide(state);
        applyDecision(state, decision);
        if (!decision.clarification().isEmpty()) {
            log.info("[supervisor] needs clarification");
            return done(decision.clarification());
        }
        log.info("[supervisor] plan: {} | reasoning: {}", state.getPlan(), decision.reasoning());

        runDataAgents(state, plannedDataAgents(state));
        return afterDataAgents(state, state.planned("budget"));
    }

    /** Continues a paused plan with the user's decision: action is "approve" or "changes". */
    public WorkflowOutcome resume(WorkflowState state, String action, String feedback) {
        if ("approve".equals(action)) {
            log.info("[review] approved");
            state.setReviewFeedback("");
        } else {
            requestChanges(state, feedback);
        }

        ReviewRouting.Route route = ReviewRouting.afterReview(state.getReviewFeedback(), state.getPlan());
        log.info("[review] next step: {} {}", route.next(), route.dataAgents());
        switch (route.next()) {
            case FINAL -> {
                return finish(state);
            }
            case DATA_AGENTS -> {
                runDataAgents(state, route.dataAgents());
                return afterDataAgents(state, state.planned("budget"));
            }
            case BUDGET -> {
                return afterDataAgents(state, true);
            }
            default -> {
                return afterDataAgents(state, false);
            }
        }
    }

    private static void applyDecision(WorkflowState state, SupervisorAgent.Decision decision) {
        state.setPlan(new ArrayList<>(decision.plan()));
        state.setClarification(decision.clarification());
        state.setUserQuery(decision.query());
        state.setTripConstraints(decision.constraints());
        state.setReasoning(decision.reasoning());
    }

    /** The agents that run again read userQuery, so the change request is appended to it. */
    private static void requestChanges(WorkflowState state, String feedback) {
        String change = feedback == null || feedback.isBlank() ? "Please improve the plan." : feedback.strip();
        log.info("[review] changes requested");
        state.setReviewFeedback(change);
        state.setUserQuery(state.getUserQuery() + "\nChange request: " + change);
        state.setRevisionCount(state.getRevisionCount() + 1);
    }

    private static List<String> plannedDataAgents(WorkflowState state) {
        List<String> agents = new ArrayList<>();
        for (String agent : ReviewRouting.PARALLEL_AGENTS) {
            if (state.planned(agent)) {
                agents.add(agent);
            }
        }
        return agents;
    }

    /** Budget (if wanted), then itinerary and review, or straight to the final answer. */
    private WorkflowOutcome afterDataAgents(WorkflowState state, boolean runBudget) {
        if (runBudget) {
            state.setBudgetAnalysis(budget.run(state));
        }
        if (!state.planned("itinerary")) {
            return finish(state);
        }
        state.setItinerary(itinerary.run(state));
        if (!needsReview(state)) {
            state.setReviewFeedback("");
            return finish(state);
        }
        log.info("[review] waiting for the user: approve or request changes");
        return new WorkflowOutcome(WorkflowOutcome.Status.NEEDS_REVIEW, state.getItinerary());
    }

    private boolean needsReview(WorkflowState state) {
        return props.workflow().humanReview() && state.getRevisionCount() < props.workflow().maxRevisions();
    }

    private WorkflowOutcome finish(WorkflowState state) {
        String answer = finalAgent.run(state);
        state.setSummary(memoryWriter.run(state, answer));
        return done(answer);
    }

    private static WorkflowOutcome done(String answer) {
        return new WorkflowOutcome(WorkflowOutcome.Status.DONE, answer);
    }

    /**
     * Runs the flight, hotel and weather agents at the same time and waits for all of them.
     * The agents only read the state; the results are stored here, after the join.
     */
    private void runDataAgents(WorkflowState state, List<String> agents) {
        log.info("[workflow] running in parallel: {}", agents);
        Map<String, Supplier<String>> tasks = new LinkedHashMap<>();
        for (String agent : agents) {
            tasks.put(agent, () -> runDataAgent(agent, state));
        }
        Parallel.run(tasks).forEach((agent, result) -> storeResult(state, agent, result));
        log.info("[gather] all parallel agents finished");
    }

    private String runDataAgent(String agent, WorkflowState state) {
        return switch (agent) {
            case "flight" -> flight.run(state);
            case "hotel" -> hotel.run(state);
            default -> weather.run(state);
        };
    }

    private static void storeResult(WorkflowState state, String agent, String result) {
        switch (agent) {
            case "flight" -> state.setFlightResults(result);
            case "hotel" -> state.setHotelResults(result);
            default -> state.setWeatherResults(result);
        }
    }
}
