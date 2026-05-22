package com.mju.capstone_backend.domain.user.service;

import reactor.core.publisher.Mono;

public interface UserService {

    Mono<Void> signup(String clerkId);

    Mono<Void> deleteAccount(String clerkId);
}
