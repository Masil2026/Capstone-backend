package com.mju.capstone_backend.domain.chatmessage.repository;

import com.mju.capstone_backend.domain.chatmessage.entity.ChatMessage;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface ChatMessageRepository extends ReactiveCrudRepository<ChatMessage, UUID> {

    @Query("SELECT * FROM chat_messages WHERE room_id = :roomId ORDER BY created_at DESC LIMIT :limit")
    Flux<ChatMessage> findByRoomIdOrderByCreatedAtDesc(UUID roomId, int limit);

    @Query("SELECT * FROM chat_messages WHERE room_id = :roomId AND created_at < :cursor ORDER BY created_at DESC LIMIT :limit")
    Flux<ChatMessage> findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc(UUID roomId, OffsetDateTime cursor, int limit);

    @Query("UPDATE chat_messages SET embedding = CAST(:embedding AS vector) WHERE id = :id")
    Mono<Void> updateEmbedding(UUID id, String embedding);

    @Query("UPDATE chat_messages SET action_result = CAST(:actionResult AS jsonb) WHERE id = :id")
    Mono<Void> updateActionResult(UUID id, String actionResult);
}
