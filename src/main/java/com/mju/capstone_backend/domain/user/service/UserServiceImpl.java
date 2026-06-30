package com.mju.capstone_backend.domain.user.service;

import com.mju.capstone_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final ClerkApiClient clerkApiClient;

    @Override
    public Mono<Void> signup(String clerkId) {
        return userRepository.insertIfNotExists(clerkId);
    }

    @Override
    public Mono<Void> deleteAccount(String clerkId) {
        return userRepository.existsById(clerkId)
                .flatMap(exists -> exists ? userRepository.deleteById(clerkId) : Mono.empty())
                .then(Mono.defer(() -> clerkApiClient.deleteUser(clerkId)));
    }
}
