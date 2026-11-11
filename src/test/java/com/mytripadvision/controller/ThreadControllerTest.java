package com.mytripadvision.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mytripadvision.dto.MessageListResponse;
import com.mytripadvision.dto.MessageResponse;
import com.mytripadvision.dto.ThreadListResponse;
import com.mytripadvision.dto.ThreadSummaryResponse;
import com.mytripadvision.service.HistoryService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@WebMvcTest({ThreadController.class, HomeController.class})
class ThreadControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean HistoryService history;
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void list_returnsThreadsWithSnakeCaseFields() throws Exception {
        when(history.listThreads("u1")).thenReturn(new ThreadListResponse(
                List.of(new ThreadSummaryResponse("user_1", Instant.parse("2026-01-01T10:00:00Z"), "Plan Tokyo"))));

        mvc.perform(get("/api/threads").param("user_id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.threads[0].thread_id").value("user_1"))
                .andExpect(jsonPath("$.threads[0].title").value("Plan Tokyo"))
                .andExpect(jsonPath("$.threads[0].updated_at").value("2026-01-01T10:00:00Z"));
    }

    @Test
    void list_withoutUserId_returns400() throws Exception {
        mvc.perform(get("/api/threads")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));

        verifyNoInteractions(history);
    }

    @Test
    void messages_includeMetaForTheReviewButtons() throws Exception {
        when(history.messages("user_1", "u1")).thenReturn(new MessageListResponse(List.of(
                new MessageResponse("assistant", "draft", json.readTree("{\"status\":\"needs_review\"}"),
                        Instant.parse("2026-01-01T10:00:00Z")))));

        mvc.perform(get("/api/threads/user_1/messages").param("user_id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].role").value("assistant"))
                .andExpect(jsonPath("$.messages[0].meta.status").value("needs_review"));
    }

    @Test
    void delete_isIdempotent_andReturnsSuccess() throws Exception {
        mvc.perform(delete("/api/threads/user_1").param("user_id", "u1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(history).deleteThread("user_1", "u1");
    }

    @Test
    void home_forwardsToTheBundledUi_andHealthAnswers() throws Exception {
        mvc.perform(get("/")).andExpect(forwardedUrl("/static/index.html"));
        mvc.perform(get("/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.message").value("AI Travel Planner API is running"));
    }
}
