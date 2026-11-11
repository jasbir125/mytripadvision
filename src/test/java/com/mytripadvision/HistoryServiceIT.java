package com.mytripadvision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mytripadvision.exception.NoPendingReviewException;
import com.mytripadvision.exception.ThreadNotFoundException;
import com.mytripadvision.exception.VersionConflictException;
import com.mytripadvision.service.HistoryService;
import com.mytripadvision.support.AbstractIT;
import com.mytripadvision.workflow.WorkflowOutcome;
import com.mytripadvision.workflow.WorkflowState;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class HistoryServiceIT extends AbstractIT {

    @Autowired HistoryService history;

    private static String id() {
        return "t_" + UUID.randomUUID().toString().replace("-", "");
    }

    private static WorkflowState state(String question) {
        WorkflowState state = new WorkflowState();
        state.setQuestion(question);
        state.setUserQuery(question);
        state.setItinerary("ITIN");
        state.setPlan(List.of("itinerary"));
        state.setTripConstraints(new LinkedHashMap<>(Map.of("destination", "Rome")));
        state.setSummary("about Rome");
        return state;
    }

    private void save(String threadId, String userId, Long version, WorkflowState state, WorkflowOutcome.Status status) {
        history.saveQuestionTurn(threadId, userId, version, state, new WorkflowOutcome(status, WorkflowOutcome.FINAL, "answer"), Map.of("status", status.wire()));
    }

    @Test
    void pausedState_survivesTheRoundTrip_andIsClearedWhenTheTurnCompletes() {
        String threadId = id();
        WorkflowState state = state("Plan Rome");
        save(threadId, "u1", null, state, WorkflowOutcome.Status.NEEDS_REVIEW);

        HistoryService.PausedThread paused = history.loadPaused(threadId, "u1");
        assertThat(paused.state().getItinerary()).isEqualTo("ITIN");
        assertThat(paused.state().getPlan()).containsExactly("itinerary");
        assertThat(paused.state().getTripConstraints()).containsEntry("destination", "Rome");

        history.saveReviewTurn(threadId, "u1", paused.version(), paused.state(),
                new WorkflowOutcome(WorkflowOutcome.Status.DONE, WorkflowOutcome.FINAL, "final"), Map.of("status", "done"));

        assertThatThrownBy(() -> history.loadPaused(threadId, "u1")).isInstanceOf(NoPendingReviewException.class);
        HistoryService.ThreadContext context = history.openThread(threadId, "u1");
        assertThat(context.summary()).isEqualTo("about Rome");
        assertThat(context.tripConstraints()).containsEntry("destination", "Rome");
        assertThat(context.userMessageCount()).isEqualTo(1);
        assertThat(context.previousQuestions()).containsExactly("Plan Rome");
        assertThat(history.messages(threadId, "u1").messages()).extracting("role")
                .containsExactly("user", "assistant", "assistant");
    }

    @Test
    void aStaleVersion_isRejectedWithAConflict_andNothingIsSaved() {
        String threadId = id();
        save(threadId, "u1", null, state("Plan Rome"), WorkflowOutcome.Status.NEEDS_REVIEW);
        HistoryService.PausedThread paused = history.loadPaused(threadId, "u1");
        history.saveReviewTurn(threadId, "u1", paused.version(), paused.state(),
                new WorkflowOutcome(WorkflowOutcome.Status.DONE, WorkflowOutcome.FINAL, "final"), Map.of());

        assertThatThrownBy(() -> history.saveReviewTurn(threadId, "u1", paused.version(), paused.state(),
                new WorkflowOutcome(WorkflowOutcome.Status.DONE, WorkflowOutcome.FINAL, "again"), Map.of()))
                .isInstanceOf(VersionConflictException.class);
        assertThat(history.messages(threadId, "u1").messages()).hasSize(3);
    }

    @Test
    void creatingTheSameThreadTwice_violatesTheNamedUniqueConstraint() {
        String threadId = id();
        save(threadId, "u1", null, state("first"), WorkflowOutcome.Status.DONE);

        assertThatThrownBy(() -> save(threadId, "u1", null, state("second"), WorkflowOutcome.Status.DONE))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_chat_threads_thread_id");
    }

    @Test
    void aThreadBelongsToItsUser() {
        String threadId = id();
        save(threadId, "owner", null, state("Plan Rome"), WorkflowOutcome.Status.NEEDS_REVIEW);

        assertThatThrownBy(() -> history.openThread(threadId, "intruder")).isInstanceOf(ThreadNotFoundException.class);
        assertThatThrownBy(() -> history.loadPaused(threadId, "intruder")).isInstanceOf(NoPendingReviewException.class);
        assertThat(history.listThreads("intruder").threads()).isEmpty();
        assertThat(history.messages(threadId, "intruder").messages()).isEmpty();
    }

    @Test
    void threadList_isNewestFirst_andTheTitleIsTheFirstQuestionCutToTheColumnSize() {
        String user = "list-" + UUID.randomUUID();
        String older = id();
        String newer = id();
        save(older, user, null, state("x".repeat(500)), WorkflowOutcome.Status.DONE);
        save(newer, user, null, state("second chat"), WorkflowOutcome.Status.DONE);

        var threads = history.listThreads(user).threads();

        assertThat(threads).extracting("threadId").containsExactly(newer, older);
        assertThat(threads.get(1).title()).hasSize(300);
    }

    @Test
    void deletingAThread_removesMessages_andIsSafeToRepeat() {
        String threadId = id();
        save(threadId, "u1", null, state("Plan Rome"), WorkflowOutcome.Status.DONE);

        history.deleteThread(threadId, "u1");
        history.deleteThread(threadId, "u1");

        assertThat(history.messages(threadId, "u1").messages()).isEmpty();
        assertThat(history.openThread(threadId, "u1").version()).isNull();
    }

}
