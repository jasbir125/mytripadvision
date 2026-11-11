package com.mytripadvision.agent;

import com.mytripadvision.client.LlmClient;
import com.mytripadvision.config.AppProperties;
import com.mytripadvision.workflow.WorkflowState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Keeps a short rolling summary of the chat so later prompts stay small. Refreshed every Nth question. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MemoryWriter {

    private static final String PROMPT = """
            Update the summary of a travel-planning chat in at most 80 words.
            Keep only facts that matter later: destination, origin, dates, budget, preferences, decisions made.

            Current summary:
            {summary}

            Latest question:
            {question}

            Latest answer (shortened):
            {answer}

            Return only the updated summary.""";

    private final LlmClient llm;
    private final AppProperties props;

    /** @return the new summary, or the current one when this question is not due for a refresh */
    public String run(WorkflowState state, String answer) {
        if (state.getQuestionNumber() % props.workflow().summaryEvery() != 0) {
            return state.getSummary();
        }
        String current = state.getSummary();
        String prompt = PROMPT
                .replace("{summary}", current == null || current.isBlank() ? "(none)" : current)
                .replace("{answer}", Texts.clip(answer, 800))
                .replace("{question}", state.getUserQuery());
        String summary = llm.complete(state.getUsage(), null, prompt).strip();
        state.addLlmCalls(1);
        log.info("[memory] summary updated ({} chars)", summary.length());
        return summary;
    }
}
