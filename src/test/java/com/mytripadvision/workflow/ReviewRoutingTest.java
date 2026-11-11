package com.mytripadvision.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewRoutingTest {

    private static final List<String> FULL_PLAN = List.of("flight", "hotel", "weather", "budget", "itinerary");

    @Test
    void approval_goesToTheFinalAnswer() {
        assertThat(ReviewRouting.afterReview("", FULL_PLAN).next()).isEqualTo(ReviewRouting.Next.FINAL);
    }

    @Test
    void flightFeedback_rerunsOnlyTheFlightAgent() {
        var route = ReviewRouting.afterReview("Find me a cheaper airline", FULL_PLAN);

        assertThat(route.next()).isEqualTo(ReviewRouting.Next.DATA_AGENTS);
        assertThat(route.dataAgents()).containsExactly("flight");
    }

    @Test
    void placeChange_rerunsHotelsAndWeather() {
        var route = ReviewRouting.afterReview("Go to a beach instead", FULL_PLAN);

        assertThat(route.dataAgents()).containsExactly("hotel", "weather");
    }

    @Test
    void agentsOutsideThePlan_stillRerun_whenTheFeedbackPointsAtThem() {
        var route = ReviewRouting.afterReview("a different hotel please", List.of("itinerary"));

        assertThat(route.dataAgents()).containsExactly("hotel");
    }

    @Test
    void moneyFeedback_rerunsBudget_onlyWhenBudgetIsPlanned() {
        assertThat(ReviewRouting.afterReview("make it cheaper", FULL_PLAN).next()).isEqualTo(ReviewRouting.Next.BUDGET);
        assertThat(ReviewRouting.afterReview("make it cheaper", List.of("itinerary")).next())
                .isEqualTo(ReviewRouting.Next.ITINERARY);
    }

    @Test
    void otherFeedback_rerunsTheItinerary() {
        assertThat(ReviewRouting.afterReview("add more museums", FULL_PLAN).next()).isEqualTo(ReviewRouting.Next.ITINERARY);
    }
}
