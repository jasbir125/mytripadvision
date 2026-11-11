package com.mytripadvision.workflow;

/** How a workflow run ended: finished with an answer, or paused with a draft plan for the user to review. */
public record WorkflowOutcome(Status status, String answer) {

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
