package com.mytripadvision.service;

import com.mytripadvision.dto.MessageListResponse;
import com.mytripadvision.dto.MessageResponse;
import com.mytripadvision.dto.ThreadListResponse;
import com.mytripadvision.dto.ThreadSummaryResponse;
import com.mytripadvision.exception.NoPendingReviewException;
import com.mytripadvision.exception.ThreadNotFoundException;
import com.mytripadvision.exception.VersionConflictException;
import com.mytripadvision.model.ChatMessage;
import com.mytripadvision.model.ChatThread;
import com.mytripadvision.repository.ChatMessageRepository;
import com.mytripadvision.repository.ChatThreadRepository;
import com.mytripadvision.workflow.WorkflowOutcome;
import com.mytripadvision.workflow.WorkflowState;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Chats and the paused workflow state. Transactions are short; no remote call runs inside one. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
@Slf4j
public class HistoryService {

    /** What a new question needs to know about its thread. {@code version} is null for a thread not saved yet. */
    public record ThreadContext(String threadId, Long version, String summary, Map<String, Object> tripConstraints,
                                List<String> previousQuestions, long userMessageCount) {
    }

    /** A plan waiting for review, with the version the answer must be saved against. */
    public record PausedThread(WorkflowState state, Long version) {
    }

    private final ChatThreadRepository threads;
    private final ChatMessageRepository messages;
    private final JsonMapper json;
    private final Clock clock;

    /** @throws ThreadNotFoundException when the thread belongs to another user */
    public ThreadContext openThread(String threadId, String userId) {
        Optional<ChatThread> found = threads.findByThreadId(threadId);
        if (found.isEmpty()) {
            return new ThreadContext(threadId, null, "", new LinkedHashMap<>(), List.of(), 0);
        }
        ChatThread thread = found.get();
        if (!thread.getUserId().equals(userId)) {
            throw new ThreadNotFoundException();
        }
        List<String> previous = new ArrayList<>(messages.findTop2ByThreadPkAndRoleOrderByIdDesc(
                thread.getId(), ChatMessage.USER).stream().map(ChatMessage::getContent).toList());
        java.util.Collections.reverse(previous);
        return new ThreadContext(threadId, thread.getVersion(), thread.getSummary(),
                readMap(thread.getTripConstraints()), previous,
                messages.countByThreadPkAndRole(thread.getId(), ChatMessage.USER));
    }

    /** @throws NoPendingReviewException when nothing is paused on this chat */
    public PausedThread loadPaused(String threadId, String userId) {
        ChatThread thread = threads.findByThreadIdAndUserId(threadId, userId)
                .filter(ChatThread::isAwaitingReview).orElseThrow(NoPendingReviewException::new);
        return new PausedThread(json.readValue(thread.getWorkflowState(), WorkflowState.class), thread.getVersion());
    }

    /** Saves the result of a new question: the user message and the answer (thread created or updated). */
    @Transactional
    public void saveQuestionTurn(String threadId, String userId, Long expectedVersion, WorkflowState state,
                                 WorkflowOutcome outcome, Map<String, Object> meta) {
        Instant now = clock.instant();
        String constraints = json.writeValueAsString(state.getTripConstraints());
        ChatThread thread;
        if (expectedVersion == null) {
            thread = ChatThread.start(threadId, userId, state.getQuestion(), now);
        } else {
            thread = threads.findByThreadIdAndUserId(threadId, userId).orElseThrow(VersionConflictException::new);
            if (!expectedVersion.equals(thread.getVersion())) {
                throw new VersionConflictException();
            }
        }
        if (outcome.status() == WorkflowOutcome.Status.NEEDS_REVIEW) {
            thread.pauseForReview(json.writeValueAsString(state), state.getSummary(), constraints, now);
        } else {
            thread.completeTurn(state.getSummary(), constraints, now);
        }
        thread = threads.saveAndFlush(thread);
        messages.save(ChatMessage.of(thread.getId(), ChatMessage.USER, state.getQuestion(), null, now));
        messages.save(ChatMessage.of(thread.getId(), ChatMessage.ASSISTANT, outcome.answer(),
                json.writeValueAsString(meta), now));
        log.info("Thread saved threadId={} status={}", threadId, thread.getStatus());
    }

    /** Saves the result of a review answer: only the assistant message is added. */
    @Transactional
    public void saveReviewTurn(String threadId, String userId, Long expectedVersion, WorkflowState state,
                               WorkflowOutcome outcome, Map<String, Object> meta) {
        Instant now = clock.instant();
        ChatThread thread = threads.findByThreadIdAndUserId(threadId, userId).orElseThrow(VersionConflictException::new);
        if (!expectedVersion.equals(thread.getVersion())) {
            throw new VersionConflictException();
        }
        String constraints = json.writeValueAsString(state.getTripConstraints());
        if (outcome.status() == WorkflowOutcome.Status.NEEDS_REVIEW) {
            thread.pauseForReview(json.writeValueAsString(state), state.getSummary(), constraints, now);
        } else {
            thread.completeTurn(state.getSummary(), constraints, now);
        }
        thread = threads.saveAndFlush(thread);
        messages.save(ChatMessage.of(thread.getId(), ChatMessage.ASSISTANT, outcome.answer(),
                json.writeValueAsString(meta), now));
        log.info("Thread saved threadId={} status={}", threadId, thread.getStatus());
    }

    /** The user's chats, newest first (at most 50). */
    public ThreadListResponse listThreads(String userId) {
        return new ThreadListResponse(threads.findTop50ByUserIdOrderByUpdatedAtDesc(userId).stream()
                .map(ThreadSummaryResponse::from).toList());
    }

    public MessageListResponse messages(String threadId, String userId) {
        return threads.findByThreadIdAndUserId(threadId, userId)
                .map(t -> new MessageListResponse(messages.findByThreadPkOrderByIdAsc(t.getId()).stream()
                        .map(m -> new MessageResponse(m.getRole(), m.getContent(), parse(m.getMeta()), m.getCreatedAt()))
                        .toList()))
                .orElseGet(() -> new MessageListResponse(List.of()));
    }

    @Transactional
    public void deleteThread(String threadId, String userId) {
        threads.findByThreadIdAndUserId(threadId, userId).ifPresent(t -> {
            threads.delete(t);
            log.info("Thread deleted threadId={}", threadId);
        });
    }

    private JsonNode parse(String text) {
        return text == null ? null : json.readTree(text);
    }

    private Map<String, Object> readMap(String text) {
        return json.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() { });
    }
}
