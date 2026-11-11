package com.mytripadvision.agent;

import com.mytripadvision.client.LlmClient;
import com.mytripadvision.workflow.WorkflowState;

/** What every agent does the same way. */
final class Agents {

    private Agents() {
    }

    /** One call to the normal model, counted on the state so the response can report how many calls were made. */
    static String ask(LlmClient llm, WorkflowState state, String systemPrompt, String prompt) {
        String reply = llm.complete(state.getUsage(), systemPrompt, prompt);
        state.addLlmCalls(1);
        return reply;
    }
}
