package com.mju.capstone_backend.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class WebClientConfig {

    @Value("${ai.agent.url}")
    private String aiAgentUrl;

    @Value("${clerk.api.secret-key}")
    private String clerkSecretKey;

    @Primary
    @Bean
    public WebClient aiWebClient() {
        return WebClient.builder()
                .baseUrl(aiAgentUrl)
                .build();
    }

    @Bean
    public WebClient clerkWebClient() {
        return WebClient.builder()
                .baseUrl("https://api.clerk.com/v1")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + clerkSecretKey)
                .build();
    }
}