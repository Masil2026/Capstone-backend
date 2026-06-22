package com.mju.capstone_backend.domain.chatmessage.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("chat_messages")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatMessage implements Persistable<UUID> {

    @Id
    private UUID id;

    private UUID roomId;
    private String role;
    private String content;
    @Getter(AccessLevel.NONE)
    private Json actionResult;
    private OffsetDateTime createdAt;

    @Transient
    private boolean newEntity = false;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public String getActionResult() { return actionResult != null ? actionResult.asString() : null; }

    public static ChatMessage of(UUID roomId, String role, String content) {
        ChatMessage msg = new ChatMessage();
        msg.id = UUID.randomUUID();
        msg.roomId = roomId;
        msg.role = role;
        msg.content = content;
        msg.createdAt = OffsetDateTime.now();
        msg.newEntity = true;
        return msg;
    }

    public static ChatMessage of(UUID roomId, String role, String content, String actionResult) {
        ChatMessage msg = of(roomId, role, content);
        if (actionResult != null) msg.actionResult = Json.of(actionResult);
        return msg;
    }
}
