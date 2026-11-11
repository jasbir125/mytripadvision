package com.mytripadvision.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Body of POST /api/travel/review: approve the plan or ask for changes. */
public record ReviewRequest(
        @NotBlank(message = "Thread id is required.") @Size(max = 64, message = "Invalid thread id.") String threadId,
        @Size(max = 100, message = "Invalid user id.") String userId,
        @NotNull(message = "Action is required.")
        @Pattern(regexp = "approve|changes", message = "Action must be approve or changes.") String action,
        @Size(max = 5000, message = "Feedback is too long.") String feedback) {

    public ReviewRequest {
        if (userId == null || userId.isBlank()) {
            userId = "anonymous";
        }
        if (feedback == null) {
            feedback = "";
        }
    }

    /** Asking for changes without saying what to change would use up one of the two revisions for nothing. */
    @AssertTrue(message = "Please describe what you want changed.")
    public boolean isFeedbackGivenWhenChangesAreRequested() {
        return !"changes".equals(action) || !feedback.isBlank();
    }
}
