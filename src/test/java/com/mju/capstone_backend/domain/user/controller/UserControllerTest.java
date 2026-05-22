package com.mju.capstone_backend.domain.user.controller;

import com.mju.capstone_backend.domain.user.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(locations = "file:.env")
@DisplayName("UserController 슬라이스 테스트")
class UserControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private UserService userService;

    @Test
    @DisplayName("유효한 JWT로 요청 시 200 반환 및 서비스 호출")
    void signup_withValidJwt_returns200() {
        String clerkId = "user_testClerkId";
        when(userService.signup(clerkId)).thenReturn(Mono.empty());

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(clerkId)))
                .post()
                .uri("/api/v1/users/signup")
                .exchange()
                .expectStatus().isOk();

        verify(userService).signup(clerkId);
    }

    @Test
    @DisplayName("JWT 없이 요청 시 401 반환")
    void signup_withoutJwt_returns401() {
        webTestClient
                .post()
                .uri("/api/v1/users/signup")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("회원탈퇴 - 유효한 JWT로 요청 시 204 반환 및 서비스 호출")
    void deleteAccount_withValidJwt_returns204() {
        String clerkId = "user_testClerkId";
        when(userService.deleteAccount(clerkId)).thenReturn(Mono.empty());

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(clerkId)))
                .delete()
                .uri("/api/v1/users/signout")
                .exchange()
                .expectStatus().isNoContent();

        verify(userService).deleteAccount(clerkId);
    }

    @Test
    @DisplayName("회원탈퇴 - JWT 없이 요청 시 401 반환")
    void deleteAccount_withoutJwt_returns401() {
        webTestClient
                .delete()
                .uri("/api/v1/users/signout")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
