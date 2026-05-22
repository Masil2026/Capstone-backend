package com.mju.capstone_backend.domain.user.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class ClerkApiClient {

    private final WebClient clerkWebClient;

    public ClerkApiClient(@Qualifier("clerkWebClient") WebClient clerkWebClient) {
        this.clerkWebClient = clerkWebClient;
    }

    public Mono<Void> deleteUser(String clerkId) {
        return clerkWebClient.delete()
                .uri("/users/{clerkId}", clerkId)
                .exchangeToMono(response -> {
                    int status = response.statusCode().value();
                    if (status == 200 || status == 404) {
                        return response.releaseBody().then();
                    }
                    return response.bodyToMono(String.class)
                            .flatMap(body -> {
                                log.error("Clerk API delete failed. status={}, body={}", status, body);
                                return Mono.<Void>error(new ResponseStatusException(
                                        HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Failed to delete user from Clerk."
                                ));
                            });
                });
    }
}
