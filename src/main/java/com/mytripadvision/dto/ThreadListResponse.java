package com.mytripadvision.dto;

import java.util.List;

/** The user's most recent chats (at most 50, newest first). */
public record ThreadListResponse(List<ThreadSummaryResponse> threads) {
}
