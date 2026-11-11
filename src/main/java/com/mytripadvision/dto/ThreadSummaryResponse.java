package com.mytripadvision.dto;

import com.mytripadvision.model.ChatThread;
import java.time.Instant;

public record ThreadSummaryResponse(String threadId, Instant updatedAt, String title) {

    public static ThreadSummaryResponse from(ChatThread thread) {
        return new ThreadSummaryResponse(thread.getThreadId(), thread.getUpdatedAt(), thread.getTitle());
    }
}
