package com.mju.capstone_backend.domain.chatroom.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Table("chat_rooms")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatRoom implements Persistable<UUID> {

    @Id
    private UUID id;

    private String clerkId;
    private String name;
    private String aiSummary;
    private String preferences;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Transient
    private boolean newEntity = false;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public static ChatRoom of(String clerkId, String name) {
        ChatRoom chatRoom = new ChatRoom();
        chatRoom.id = UUID.randomUUID();
        chatRoom.clerkId = clerkId;
        chatRoom.name = name;
        chatRoom.createdAt = OffsetDateTime.now();
        chatRoom.updatedAt = OffsetDateTime.now();
        chatRoom.newEntity = true;
        return chatRoom;
    }

    public void updateName(String name) {
        this.name = name;
        this.updatedAt = OffsetDateTime.now();
    }

    public void updateMemory(String aiSummary, String preferences) {
        if (aiSummary != null) this.aiSummary = aiSummary;
        if (preferences != null) this.preferences = preferences;
        this.updatedAt = OffsetDateTime.now();
    }
}
