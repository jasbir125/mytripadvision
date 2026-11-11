package com.mytripadvision.workflow;

/**
 * How a workflow run ended: finished with an answer, or paused with a draft plan for the user to review.
 * {@code agent} names the agent that wrote the answer text, so the UI can show where the reply came from.
 */
public record WorkflowOutcome(Status status, String agent, String answer) {

    /** The agents that can write the answer the user reads. */
    public static final String GUARDRAILS = "guardrails";
    public static final String SUPERVISOR = "supervisor";
    public static final String ITINERARY = "itinerary";
    public static final String FINAL = "final";

    public enum Status {
        DONE("done"),
        NEEDS_REVIEW("needs_review");

        private final String wire;

        Status(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }
    }
}
