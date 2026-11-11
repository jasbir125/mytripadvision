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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
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

    private final ExecutorService virtualThreads = Executors.newVirtualThreadPerTaskExecutor();

    /** Runs a new question from the start. */
    public WorkflowOutcome start(WorkflowState state) {
        String problem = guardrails.check(state.getUserQuery());
        if (!problem.isEmpty()) {
            state.setClarification(problem);
            state.setPlan(new ArrayList<>());
            return new WorkflowOutcome(WorkflowOutcome.Status.DONE, problem);
        }

        SupervisorAgent.Decision decision = supervisor.decide(state);
        state.setPlan(new ArrayList<>(decision.plan()));
        state.setClarification(decision.clarification());
        state.setUserQuery(decision.query());
        state.setTripConstraints(decision.constraints());
        state.setReasoning(decision.reasoning());
        if (!decision.clarification().isEmpty()) {
            log.info("[supervisor] needs clarification");
            return new WorkflowOutcome(WorkflowOutcome.Status.DONE, decision.clarification());
        }
        log.info("[supervisor] plan: {} | reasoning: {}", state.getPlan(), decision.reasoning());

        List<String> dataAgents = new ArrayList<>();
        for (String agent : ReviewRouting.PARALLEL_AGENTS) {
            if (state.planned(agent)) {
                dataAgents.add(agent);
            }
        }
        runDataAgents(state, dataAgents);
        return afterDataAgents(state, state.planned("budget"));
    }

    /** Continues a paused plan with the user's decision: action is "approve" or "changes". */
    public WorkflowOutcome resume(WorkflowState state, String action, String feedback) {
        if ("approve".equals(action)) {
            log.info("[review] approved");
            state.setReviewFeedback("");
        } else {
            String change = feedback == null || feedback.isBlank() ? "Please improve the plan." : feedback.strip();
            log.info("[review] changes requested");
            state.setReviewFeedback(change);
            // the agents that run again read userQuery, so they see the change request
            state.setUserQuery(state.getUserQuery() + "\nChange request: " + change);
            state.setRevisionCount(state.getRevisionCount() + 1);
        }

        ReviewRouting.Route route = ReviewRouting.afterReview(state.getReviewFeedback(), state.getPlan());
        log.info("[review] next step: {} {}", route.next(), route.dataAgents());
        switch (route.next()) {
            case FINAL:
                return finish(state);
            case DATA_AGENTS:
                runDataAgents(state, route.dataAgents());
                return afterDataAgents(state, state.planned("budget"));
            case BUDGET:
                return afterDataAgents(state, true);
            default:
                return afterDataAgents(state, false);
        }
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

        boolean needsReview = props.workflow().humanReview()
                && state.getRevisionCount() < props.workflow().maxRevisions();
        if (!needsReview) {
            state.setReviewFeedback("");
            return finish(state);
        }
        log.info("[review] waiting for the user: approve or request changes");
        return new WorkflowOutcome(WorkflowOutcome.Status.NEEDS_REVIEW, state.getItinerary());
    }

    private WorkflowOutcome finish(WorkflowState state) {
        String answer = finalAgent.run(state);
        state.setSummary(memoryWriter.run(state, answer));
        return new WorkflowOutcome(WorkflowOutcome.Status.DONE, answer);
    }

    /**
     * Runs the flight, hotel and weather agents at the same time, each on its own virtual thread, and waits for
     * all of them. The agents only read the state; the results are stored here, after the join.
     */
    private void runDataAgents(WorkflowState state, List<String> agents) {
        log.info("[workflow] running in parallel: {}", agents);
        Map<String, String> mdc = MDC.getCopyOfContextMap();   // new threads start with an empty MDC
        Map<String, CompletableFuture<String>> tasks = new LinkedHashMap<>();
        for (String agent : agents) {
            tasks.put(agent, CompletableFuture.supplyAsync(() -> {
                if (mdc != null) {
                    MDC.setContextMap(mdc);
                }
                return runDataAgent(agent, state);
            }, virtualThreads));
        }

        for (Map.Entry<String, CompletableFuture<String>> task : tasks.entrySet()) {
            String result;
            try {
                result = task.getValue().join();
            } catch (CompletionException ex) {
                // hand the original exception on, so the API can answer 503 for a failing provider
                if (ex.getCause() instanceof RuntimeException cause) {
                    throw cause;
                }
                throw ex;
            }
            switch (task.getKey()) {
                case "flight" -> state.setFlightResults(result);
                case "hotel" -> state.setHotelResults(result);
                default -> state.setWeatherResults(result);
            }
        }
        log.info("[gather] all parallel agents finished");
    }

    private String runDataAgent(String agent, WorkflowState state) {
        return switch (agent) {
            case "flight" -> flight.run(state);
            case "hotel" -> hotel.run(state);
            default -> weather.run(state);
        };
    }
}
