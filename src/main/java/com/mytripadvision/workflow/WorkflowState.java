package com.mytripadvision.workflow;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Everything the workflow knows while it runs. It is stored as JSON in {@code chat_threads.workflow_state}
 * while a plan waits for review, so every field must stay serialisable.
 */
@Getter
@Setter
@NoArgsConstructor
public class WorkflowState {

    /** The query the agents work on; the supervisor rewrites it and change requests are appended. */
    private String userQuery = "";
    /** What the user typed for this question. */
    private String question = "";
    private List<String> previousQuestions = new ArrayList<>();
    private int questionNumber = 1;

    private String flightResults = "";
    private String hotelResults = "";
    private String weatherResults = "";
    private String budgetAnalysis = "";
    private String itinerary = "";

    private List<String> plan = new ArrayList<>();
    private String clarification = "";
    private String summary = "";
    private Map<String, Object> tripConstraints = new LinkedHashMap<>();
    private String reasoning = "";
    private String reviewFeedback = "";
    private int revisionCount;
    /** One line per agent whose service was down or out of quota, shown to the user next to the answer. */
    private Map<String, String> notices = new LinkedHashMap<>();
    /** LLM calls made by the current HTTP request (reset when a request starts). */
    private int llmCalls;

    /** Tokens of the current request only. Not saved with the paused plan: each request starts at zero. */
    @JsonIgnore
    private Usage usage = new Usage();

    /** Synchronized because the flight, hotel and weather agents run in parallel. */
    public synchronized void addLlmCalls(int calls) {
        this.llmCalls += calls;
    }

    /** Synchronized for the same reason. A notice stays until the same agent runs again. */
    public synchronized void addNotice(String agent, String text) {
        notices.put(agent, text);
    }

    public synchronized void clearNotice(String agent) {
        notices.remove(agent);
    }

    /** The notices as plain lines, for the response and the chat history. */
    public synchronized List<String> noticeLines() {
        return new ArrayList<>(notices.values());
    }

    public boolean planned(String agent) {
        return plan.contains(agent);
    }
}
