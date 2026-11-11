package com.mytripadvision.controller;

import com.mytripadvision.dto.MessageListResponse;
import com.mytripadvision.dto.SuccessResponse;
import com.mytripadvision.dto.ThreadListResponse;
import com.mytripadvision.service.HistoryService;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/threads")
@RequiredArgsConstructor
@Validated
public class ThreadController {

    private final HistoryService history;

    @GetMapping
    public ThreadListResponse list(@RequestParam("user_id") @NotBlank String userId) {
        return history.listThreads(userId);
    }

    @GetMapping("/{threadId}/messages")
    public MessageListResponse messages(@PathVariable String threadId, @RequestParam("user_id") @NotBlank String userId) {
        return history.messages(threadId, userId);
    }

    /** Deleting a chat that does not exist is not an error (the call is idempotent). */
    @DeleteMapping("/{threadId}")
    public SuccessResponse delete(@PathVariable String threadId, @RequestParam("user_id") @NotBlank String userId) {
        history.deleteThread(threadId, userId);
        return new SuccessResponse(true);
    }
}
