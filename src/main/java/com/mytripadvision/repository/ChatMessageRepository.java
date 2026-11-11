package com.mytripadvision.repository;

import com.mytripadvision.model.ChatMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByThreadPkOrderByIdAsc(Long threadPk);

    long countByThreadPkAndRole(Long threadPk, String role);

    List<ChatMessage> findTop2ByThreadPkAndRoleOrderByIdDesc(Long threadPk, String role);
}
