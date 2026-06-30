package com.mju.capstone_backend.domain.user.repository;

import com.mju.capstone_backend.domain.user.entity.User;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Mono;

public interface UserRepository extends ReactiveCrudRepository<User, String> {

    @Query("INSERT INTO users (clerk_id) VALUES (:clerkId) ON CONFLICT (clerk_id) DO NOTHING")
    Mono<Void> insertIfNotExists(String clerkId);
}
