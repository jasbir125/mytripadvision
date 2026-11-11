package com.mytripadvision.rag;

import com.mytripadvision.config.AppProperties;
import com.mytripadvision.repository.RagChunkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes search-result chunks older than the retention period. Safe to run on every replica (idempotent delete). */
@Component
@RequiredArgsConstructor
@Slf4j
public class RagCleanupJob {

    private final RagChunkRepository chunks;
    private final AppProperties props;

    @Scheduled(fixedDelayString = "PT1H", initialDelayString = "PT5M")
    public void cleanup() {
        try {
            int deleted = chunks.deleteOlderThan(props.rag().retention());
            if (deleted > 0) {
                log.info("[rag] removed {} expired chunks", deleted);
            }
        } catch (RuntimeException ex) {
            log.error("[rag] cleanup failed", ex);
        }
    }
}
