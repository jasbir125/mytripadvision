package com.mytripadvision.workflow;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.MDC;

/** Runs named tasks at the same time, each on its own virtual thread, and returns the results in the same order. */
public final class Parallel {

    private static final ExecutorService VIRTUAL_THREADS = Executors.newVirtualThreadPerTaskExecutor();

    private Parallel() {
    }

    /** Waits for every task. If one fails, its own exception is thrown on (so the API can answer 503 for a failing provider). */
    public static Map<String, String> run(Map<String, Supplier<String>> tasks) {
        Map<String, String> logContext = MDC.getCopyOfContextMap();   // new threads start with an empty MDC
        Map<String, CompletableFuture<String>> running = new LinkedHashMap<>();
        tasks.forEach((name, task) ->
                running.put(name, CompletableFuture.supplyAsync(() -> runWithLogContext(task, logContext), VIRTUAL_THREADS)));

        Map<String, String> results = new LinkedHashMap<>();
        running.forEach((name, future) -> results.put(name, join(future)));
        return results;
    }

    /** Copies the request id into the new thread, so its log lines belong to the request. */
    private static String runWithLogContext(Supplier<String> task, Map<String, String> logContext) {
        if (logContext != null) {
            MDC.setContextMap(logContext);
        }
        return task.get();
    }

    private static String join(CompletableFuture<String> future) {
        try {
            return future.join();
        } catch (CompletionException wrapped) {
            if (wrapped.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw wrapped;
        }
    }
}
