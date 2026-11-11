package com.mytripadvision.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /api/travel. {@code userId} is the anonymous id created by the browser. */
public record TravelRequest(
        @NotBlank(message = "Message cannot be empty.") @Size(max = 10000, message = "Message is too long.") String message,
        @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}", message = "Invalid thread id.") String threadId,
        @Size(max = 100, message = "Invalid user id.") String userId) {

    public TravelRequest {
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }
    }
}
