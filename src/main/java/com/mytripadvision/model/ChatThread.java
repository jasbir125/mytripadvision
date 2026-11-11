package com.mytripadvision.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

/**
 * A conversation owned by one user. While a plan waits for review its workflow state is stored here as JSON,
 * so the second HTTP call can resume it (also after a restart or on another replica).
 */
@Entity
@Table(name = "chat_threads")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatThread {

    public static final int TITLE_MAX = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "thread_id", nullable = false, updatable = false, length = 64)
    private String threadId;

    @Column(name = "user_id", nullable = false, updatable = false, length = 100)
    private String userId;

    @Column(name = "title", nullable = false, length = TITLE_MAX)
    private String title;

    /** Rolling summary of the chat (memory writer). */
    @Column(name = "summary", nullable = false)
    private String summary = "";

    @Column(name = "trip_constraints", nullable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String tripConstraints = "{}";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ThreadStatus status = ThreadStatus.IDLE;

    @Column(name = "workflow_state", columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String workflowState;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** The title is the first question of the chat, cut to the column size. */
    public static ChatThread start(String threadId, String userId, String firstQuestion, Instant now) {
        ChatThread thread = new ChatThread();
        thread.threadId = threadId;
        thread.userId = userId;
        thread.title = firstQuestion.length() <= TITLE_MAX ? firstQuestion : firstQuestion.substring(0, TITLE_MAX);
        thread.createdAt = now;
        thread.updatedAt = now;
        return thread;
    }

    public void completeTurn(String newSummary, String constraintsJson, Instant now) {
        this.summary = newSummary;
        this.tripConstraints = constraintsJson;
        this.status = ThreadStatus.IDLE;
        this.workflowState = null;
        this.updatedAt = now;
    }

    public void pauseForReview(String workflowStateJson, String newSummary, String constraintsJson, Instant now) {
        this.summary = newSummary;
        this.tripConstraints = constraintsJson;
        this.status = ThreadStatus.AWAITING_REVIEW;
        this.workflowState = workflowStateJson;
        this.updatedAt = now;
    }

    public boolean isAwaitingReview() {
        return status == ThreadStatus.AWAITING_REVIEW && workflowState != null;
    }
}
