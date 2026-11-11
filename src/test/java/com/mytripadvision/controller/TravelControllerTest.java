package com.mytripadvision.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.mytripadvision.dto.TravelResponse;
import com.mytripadvision.exception.ExternalServiceException;
import com.mytripadvision.exception.NoPendingReviewException;
import com.mytripadvision.exception.ThreadNotFoundException;
import com.mytripadvision.exception.VersionConflictException;
import com.mytripadvision.service.TravelService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TravelController.class)
class TravelControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TravelService travel;

    private static TravelResponse response(String status) {
        return new TravelResponse(true, status, "user_1", "the answer", "flights", "hotels", "itinerary", "budget",
                Map.of("destination", "Tokyo"), 4, "ab12cd34", List.of("flight", "itinerary"),
                Map.of("prompt", 10, "completion", 5, "total", 15, "cost_usd", 0.000005));
    }

    @Test
    void plan_returnsTheSnakeCaseContractTheUiReads() throws Exception {
        when(travel.plan(any())).thenReturn(response("needs_review"));

        mvc.perform(post("/api/travel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"Plan Tokyo\", \"thread_id\": null, \"user_id\": \"u1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value("needs_review"))
                .andExpect(jsonPath("$.thread_id").value("user_1"))
                .andExpect(jsonPath("$.answer").value("the answer"))
                .andExpect(jsonPath("$.flight_results").value("flights"))
                .andExpect(jsonPath("$.hotel_results").value("hotels"))
                .andExpect(jsonPath("$.budget_analysis").value("budget"))
                .andExpect(jsonPath("$.trip_constraints.destination").value("Tokyo"))
                .andExpect(jsonPath("$.llm_calls").value(4))
                .andExpect(jsonPath("$.request_id").value("ab12cd34"))
                .andExpect(jsonPath("$.plan[0]").value("flight"))
                .andExpect(jsonPath("$.token_usage.total").value(15))
                .andExpect(jsonPath("$.token_usage.cost_usd").value(0.000005));
    }

    @Test
    void plan_blankMessage_returns400ProblemDetails_withTheUiErrorField() throws Exception {
        mvc.perform(post("/api/travel").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("Message cannot be empty."))
                .andExpect(jsonPath("$.errors[0].field").value("message"))
                .andExpect(jsonPath("$.errors[0].code").value("required"));

        verifyNoInteractions(travel);
    }

    @Test
    void plan_invalidThreadId_returns400() throws Exception {
        mvc.perform(post("/api/travel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\": \"hi\", \"thread_id\": \"bad id\\nwith newline\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("threadId"));
    }

    @Test
    void plan_brokenJson_returns400ProblemDetails() throws Exception {
        mvc.perform(post("/api/travel").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    void plan_foreignThread_returns404() throws Exception {
        when(travel.plan(any())).thenThrow(new ThreadNotFoundException());

        mvc.perform(post("/api/travel").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Thread not found."));
    }

    @Test
    void plan_dependencyFailure_returns503_withoutRemoteDetails() throws Exception {
        when(travel.plan(any())).thenThrow(new ExternalServiceException("tavily", "HTTP 502 secret-detail"));

        mvc.perform(post("/api/travel").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"hi\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(
                        "A service this request depends on is unavailable. Please try again in a moment."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-detail"))));
    }

    @Test
    void plan_unexpectedException_returns500_withAGenericMessage() throws Exception {
        when(travel.plan(any())).thenThrow(new IllegalStateException("jdbc:postgresql://secret-host leaked"));

        mvc.perform(post("/api/travel").contentType(MediaType.APPLICATION_JSON).content("{\"message\": \"hi\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("An unexpected error occurred."))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-host"))));
    }

    @Test
    void review_approve_returnsTheFinalAnswer() throws Exception {
        when(travel.review(any())).thenReturn(response("done"));

        mvc.perform(post("/api/travel/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thread_id\": \"user_1\", \"user_id\": \"u1\", \"action\": \"approve\", \"feedback\": \"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("done"));
    }

    @Test
    void review_unknownAction_returns400() throws Exception {
        mvc.perform(post("/api/travel/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thread_id\": \"user_1\", \"action\": \"explode\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Action must be approve or changes."));

        verifyNoInteractions(travel);
    }

    @Test
    void review_changesWithoutFeedback_returns400_andNothingIsCalled() throws Exception {
        for (String feedback : new String[] {"", "   "}) {
            mvc.perform(post("/api/travel/review").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"thread_id\": \"user_1\", \"action\": \"changes\", \"feedback\": \"" + feedback + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("Please describe what you want changed."));
        }
        mvc.perform(post("/api/travel/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thread_id\": \"user_1\", \"action\": \"changes\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(travel);
    }

    @Test
    void review_changesWithFeedback_isAccepted() throws Exception {
        when(travel.review(any())).thenReturn(response("needs_review"));

        mvc.perform(post("/api/travel/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thread_id\": \"user_1\", \"action\": \"changes\", \"feedback\": \"cheaper hotel\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void review_nothingWaiting_returns409() throws Exception {
        when(travel.review(any())).thenThrow(new NoPendingReviewException());

        mvc.perform(post("/api/travel/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thread_id\": \"user_1\", \"action\": \"approve\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(
                        "No plan is waiting for review on this chat (it may already be approved)."));
    }

    @Test
    void review_concurrentAnswer_returns409() throws Exception {
        when(travel.review(any())).thenThrow(new VersionConflictException());

        mvc.perform(post("/api/travel/review").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"thread_id\": \"user_1\", \"action\": \"changes\", \"feedback\": \"more\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Version conflict"));
    }
}
