package com.mytripadvision.rag;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Indexes the knowledge base at startup. RAG is optional: a failure is logged and the app keeps running. */
@Component
@RequiredArgsConstructor
@Slf4j
public class KnowledgeIndexer implements ApplicationRunner {

    private final KnowledgeBaseService knowledge;

    @Override
    public void run(ApplicationArguments args) {
        try {
            knowledge.buildIndex();
        } catch (RuntimeException ex) {
            log.warn("[rag] setup failed, knowledge base disabled: {}", ex.getClass().getSimpleName(), ex);
        }
    }
}
