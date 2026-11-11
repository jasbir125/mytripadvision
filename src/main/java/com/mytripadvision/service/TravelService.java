package com.mytripadvision.service;

import com.mytripadvision.config.AppProperties;
import com.mytripadvision.config.Correlation;
import com.mytripadvision.dto.ReviewRequest;
import com.mytripadvision.dto.TravelRequest;
import com.mytripadvision.dto.TravelResponse;
import com.mytripadvision.exception.VersionConflictException;
import com.mytripadvision.workflow.TravelWorkflow;
import com.mytripadvision.workflow.Usage;
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
    private final AppProperties props;

    /** Starts a new question on a new or existing thread. The result is a final answer or a plan to review. */
    public TravelResponse plan(TravelRequest request) {
        String threadId = request.threadId() == null || request.threadId().isBlank() ? newThreadId() : request.threadId();
        HistoryService.ThreadContext thread = history.openThread(threadId, request.userId());
        WorkflowState state = newState(request.message().strip(), thread);

        return execute(threadId, state, "new request", () -> workflow.start(state),
                (outcome, meta) -> history.saveQuestionTurn(threadId, request.userId(), thread.version(), state, outcome, meta));
    }

    private static String newThreadId() {
        return "user_" + UUID.randomUUID().toString().replace("-", "");
    }

    /** A fresh state for one question, with what the thread already knows. */
    private WorkflowState newState(String message, HistoryService.ThreadContext thread) {
        WorkflowState state = new WorkflowState();
        state.setUsage(new Usage(props.llm().prices()));
        state.setQuestion(message);
        state.setUserQuery(message);
        state.setPreviousQuestions(new ArrayList<>(thread.previousQuestions()));
        state.setQuestionNumber((int) thread.userMessageCount() + 1);
        state.setSummary(thread.summary());
        state.setTripConstraints(new LinkedHashMap<>(thread.tripConstraints()));
        return state;
    }

    /** Continues a plan that waits for the user: action is "approve" or "changes". */
    public TravelResponse review(ReviewRequest request) {
        HistoryService.PausedThread paused = history.loadPaused(request.threadId(), request.userId());
        WorkflowState state = paused.state();
        state.setLlmCalls(0);
        state.setUsage(new Usage(props.llm().prices()));   // tokens and cost of this request only

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
        log.info("[graph] {} by {} | thread={} | plan={} | llm_calls={} | tokens={} | took {} ms", status,
                outcome.agent(), threadId, state.getPlan(), state.getLlmCalls(), state.getUsage().total(),
                (System.nanoTime() - started) / 1_000_000);

        persist(outcome, meta(requestId, outcome, state, tokenUsage), saver);

        return new TravelResponse(true, status, outcome.agent(), threadId, outcome.answer(), state.getFlightResults(),
                state.getHotelResults(), state.getItinerary(), state.getBudgetAnalysis(), state.getTripConstraints(),
                state.getLlmCalls(), requestId, state.getPlan(), state.noticeLines(), tokenUsage);
    }

    /** What is stored next to the answer in the chat history (shown by the UI as the message details). */
    private static Map<String, Object> meta(String requestId, WorkflowOutcome outcome, WorkflowState state,
                                            Map<String, Object> tokenUsage) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("request_id", requestId);
        meta.put("status", outcome.status().wire());
        meta.put("agent", outcome.agent());
        meta.put("plan", state.getPlan());
        meta.put("notices", state.noticeLines());
        meta.put("llm_calls", state.getLlmCalls());
        meta.put("token_usage", tokenUsage);
        return meta;
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
