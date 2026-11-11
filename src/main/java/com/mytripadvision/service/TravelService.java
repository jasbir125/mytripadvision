package com.mytripadvision.service;

import com.mytripadvision.config.Correlation;
import com.mytripadvision.dto.ReviewRequest;
import com.mytripadvision.dto.TravelRequest;
import com.mytripadvision.dto.TravelResponse;
import com.mytripadvision.exception.VersionConflictException;
import com.mytripadvision.workflow.TravelWorkflow;
import com.mytripadvision.workflow.WorkflowOutcome;
import com.mytripadvision.workflow.WorkflowState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Runs a travel request or a review answer. Deliberately not transactional: the workflow makes slow remote calls,
 * so each database step ({@link HistoryService}) is its own short transaction.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TravelService {

    private final TravelWorkflow workflow;
    private final HistoryService history;

    /** Starts a new question on a new or existing thread. The result is a final answer or a plan to review. */
    public TravelResponse plan(TravelRequest request) {
        String message = request.message().strip();
        String threadId = request.threadId() == null || request.threadId().isBlank()
                ? "user_" + UUID.randomUUID().toString().replace("-", "") : request.threadId();
        HistoryService.ThreadContext thread = history.openThread(threadId, request.userId());

        WorkflowState state = new WorkflowState();
        state.setQuestion(message);
        state.setUserQuery(message);
        state.setPreviousQuestions(new ArrayList<>(thread.previousQuestions()));
        state.setQuestionNumber((int) thread.userMessageCount() + 1);
        state.setSummary(thread.summary());
        state.setTripConstraints(new LinkedHashMap<>(thread.tripConstraints()));

        return execute(threadId, state, "new request", () -> workflow.start(state),
                (outcome, meta) -> history.saveQuestionTurn(threadId, request.userId(), thread.version(), state, outcome, meta));
    }

    /** Continues a plan that waits for the user: action is "approve" or "changes". */
    public TravelResponse review(ReviewRequest request) {
        HistoryService.PausedThread paused = history.loadPaused(request.threadId(), request.userId());
        WorkflowState state = paused.state();
        state.setLlmCalls(0);

        return execute(request.threadId(), state, "review answer",
                () -> workflow.resume(state, request.action(), request.feedback()),
                (outcome, meta) -> history.saveReviewTurn(request.threadId(), request.userId(), paused.version(),
                        state, outcome, meta));
    }

    private TravelResponse execute(String threadId, WorkflowState state, String kind,
                                   Supplier<WorkflowOutcome> run, Saver saver) {
        long started = System.nanoTime();
        // the request id was put in the log context by CorrelationIdFilter
        String requestId = MDC.get(Correlation.REQUEST_MDC_KEY);
        log.info("[graph] {} on thread {}", kind, threadId);

        WorkflowOutcome outcome = run.get();
        String status = outcome.status().wire();
        Map<String, Object> tokenUsage = state.getUsage().asMap();
        log.info("[graph] {} | thread={} | plan={} | llm_calls={} | tokens={} | took {} ms", status, threadId,
                state.getPlan(), state.getLlmCalls(), state.getUsage().total(), (System.nanoTime() - started) / 1_000_000);

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("request_id", requestId);
        meta.put("status", status);
        meta.put("plan", state.getPlan());
        meta.put("llm_calls", state.getLlmCalls());
        meta.put("token_usage", tokenUsage);
        persist(outcome, meta, saver);

        return new TravelResponse(true, status, threadId, outcome.answer(), state.getFlightResults(),
                state.getHotelResults(), state.getItinerary(), state.getBudgetAnalysis(), state.getTripConstraints(),
                state.getLlmCalls(), requestId, state.getPlan(), tokenUsage);
    }

    /**
     * A paused plan must be saved, or the user could not resume it, so that failure reaches the caller.
     * For a finished answer history is a bonus: a failure is logged and the answer is still returned,
     * but a version conflict (another request changed the chat, also when detected at commit) is reported.
     */
    private void persist(WorkflowOutcome outcome, Map<String, Object> meta, Saver saver) {
        if (outcome.status() == WorkflowOutcome.Status.NEEDS_REVIEW) {
            saver.save(outcome, meta);
            return;
        }
        try {
            saver.save(outcome, meta);
        } catch (VersionConflictException | OptimisticLockingFailureException conflict) {
            throw conflict;
        } catch (RuntimeException ex) {
            log.error("Could not save the chat history", ex);
        }
    }

    @FunctionalInterface
    private interface Saver {
        void save(WorkflowOutcome outcome, Map<String, Object> meta);
    }
}
