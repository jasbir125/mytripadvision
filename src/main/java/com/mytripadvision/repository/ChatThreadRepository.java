package com.mytripadvision.repository;

import com.mytripadvision.model.ChatThread;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatThreadRepository extends JpaRepository<ChatThread, Long> {

    Optional<ChatThread> findByThreadId(String threadId);

    List<ChatThread> findTop50ByUserIdOrderByUpdatedAtDesc(String userId);

    Optional<ChatThread> findByThreadIdAndUserId(String threadId, String userId);
}
