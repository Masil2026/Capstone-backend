package com.mju.capstone_backend.domain.chatroom.repository;

import com.mju.capstone_backend.domain.chatroom.entity.ChatRoom;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ChatRoomRepository extends ReactiveCrudRepository<ChatRoom, UUID> {

    Flux<ChatRoom> findByClerkIdOrderByUpdatedAtDesc(String clerkId);
}
