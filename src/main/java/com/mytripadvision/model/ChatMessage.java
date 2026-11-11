package com.mytripadvision.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.ColumnTransformer;

@Entity
@Table(name = "chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage {

    public static final String USER = "user";
    public static final String ASSISTANT = "assistant";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "thread_pk", nullable = false, updatable = false)
    private Long threadPk;

    @Column(name = "role", nullable = false, updatable = false, length = 20)
    private String role;

    @Column(name = "content", nullable = false, updatable = false)
    private String content;

    @Column(name = "meta", updatable = false, columnDefinition = "jsonb")
    @ColumnTransformer(write = "?::jsonb")
    private String meta;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public static ChatMessage of(Long threadPk, String role, String content, String metaJson, Instant now) {
        ChatMessage message = new ChatMessage();
        message.threadPk = threadPk;
        message.role = role;
        message.content = content;
        message.meta = metaJson;
        message.createdAt = now;
        return message;
    }
}
