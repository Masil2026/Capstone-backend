package com.mju.capstone_backend.domain.chatroom.controller;

import com.mju.capstone_backend.domain.chatroom.dto.CreateChatRoomRequest;
import com.mju.capstone_backend.domain.chatroom.dto.CreateChatRoomResponse;
import com.mju.capstone_backend.domain.chatroom.dto.DeleteChatRoomResponse;
import com.mju.capstone_backend.domain.chatroom.dto.GetChatRoomResponse;
import com.mju.capstone_backend.domain.chatroom.dto.GetChatRoomsResponse;
import com.mju.capstone_backend.domain.chatroom.dto.UpdateChatRoomNameRequest;
import com.mju.capstone_backend.domain.chatroom.dto.UpdateChatRoomNameResponse;
import com.mju.capstone_backend.domain.chatroom.service.ChatRoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(locations = "file:.env")
@DisplayName("ChatRoomController 슬라이스 테스트")
class ChatRoomControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ChatRoomService chatRoomService;

    private static final String CLERK_ID = "user_testClerkId";
    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID ITINERARY_ID = UUID.randomUUID();

    // ─── POST /api/v1/chat-rooms ──────────────────────────────────────────────

    @Test
    @DisplayName("채팅방 생성 - 유효한 JWT와 요청 본문으로 201 반환")
    void createChatRoom_withValidJwt_returns201() {
        CreateChatRoomResponse response = new CreateChatRoomResponse(
                ROOM_ID, "2박 3일 도쿄 여행", ITINERARY_ID, CLERK_ID, OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(chatRoomService.createChatRoom(eq(CLERK_ID), any(CreateChatRoomRequest.class)))
                .thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .post()
                .uri("/api/v1/chat-rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "origin": {"city": "서울"},
                          "destinations": [{"city": "도쿄", "start_date": "2026-05-01", "end_date": "2026-05-03"}],
                          "adultCount": 2,
                          "childCount": 0,
                          "childAges": []
                        }
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.roomId").isEqualTo(ROOM_ID.toString())
                .jsonPath("$.itineraryId").isEqualTo(ITINERARY_ID.toString());

        verify(chatRoomService).createChatRoom(eq(CLERK_ID), any(CreateChatRoomRequest.class));
    }

    @Test
    @DisplayName("채팅방 생성 - JWT 없이 요청 시 401 반환")
    void createChatRoom_withoutJwt_returns401() {
        webTestClient
                .post()
                .uri("/api/v1/chat-rooms")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {
                          "origin": {"city": "서울"},
                          "destinations": [{"city": "도쿄", "start_date": "2026-05-01", "end_date": "2026-05-03"}],
                          "adultCount": 2,
                          "childCount": 0,
                          "childAges": []
                        }
                        """)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── GET /api/v1/chat-rooms ───────────────────────────────────────────────

    @Test
    @DisplayName("채팅방 목록 조회 - 유효한 JWT로 200 반환")
    void getChatRooms_withValidJwt_returns200() {
        GetChatRoomsResponse response = new GetChatRoomsResponse(List.of());
        when(chatRoomService.getChatRooms(CLERK_ID)).thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .get()
                .uri("/api/v1/chat-rooms")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.rooms").isArray();

        verify(chatRoomService).getChatRooms(CLERK_ID);
    }

    @Test
    @DisplayName("채팅방 목록 조회 - JWT 없이 요청 시 401 반환")
    void getChatRooms_withoutJwt_returns401() {
        webTestClient
                .get()
                .uri("/api/v1/chat-rooms")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── GET /api/v1/chat-rooms/{roomId} ─────────────────────────────────────

    @Test
    @DisplayName("채팅방 상세 조회 - 유효한 JWT로 200 반환")
    void getChatRoom_withValidJwt_returns200() {
        GetChatRoomResponse response = new GetChatRoomResponse(
                ROOM_ID, "테스트 채팅방", CLERK_ID, null, null, ITINERARY_ID, OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(chatRoomService.getChatRoom(CLERK_ID, ROOM_ID)).thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .get()
                .uri("/api/v1/chat-rooms/{roomId}", ROOM_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.roomId").isEqualTo(ROOM_ID.toString());

        verify(chatRoomService).getChatRoom(CLERK_ID, ROOM_ID);
    }

    @Test
    @DisplayName("채팅방 상세 조회 - JWT 없이 요청 시 401 반환")
    void getChatRoom_withoutJwt_returns401() {
        webTestClient
                .get()
                .uri("/api/v1/chat-rooms/{roomId}", ROOM_ID)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── PATCH /api/v1/chat-rooms/{roomId}/name ───────────────────────────────

    @Test
    @DisplayName("채팅방 이름 수정 - 유효한 JWT와 요청 본문으로 200 반환")
    void updateChatRoomName_withValidJwt_returns200() {
        UpdateChatRoomNameResponse response = new UpdateChatRoomNameResponse(
                ROOM_ID, "새로운 이름", OffsetDateTime.now()
        );
        when(chatRoomService.updateChatRoomName(CLERK_ID, ROOM_ID, "새로운 이름"))
                .thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .patch()
                .uri("/api/v1/chat-rooms/{roomId}/name", ROOM_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name": "새로운 이름"}
                        """)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.roomId").isEqualTo(ROOM_ID.toString())
                .jsonPath("$.name").isEqualTo("새로운 이름");

        verify(chatRoomService).updateChatRoomName(CLERK_ID, ROOM_ID, "새로운 이름");
    }

    @Test
    @DisplayName("채팅방 이름 수정 - JWT 없이 요청 시 401 반환")
    void updateChatRoomName_withoutJwt_returns401() {
        webTestClient
                .patch()
                .uri("/api/v1/chat-rooms/{roomId}/name", ROOM_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"name": "새로운 이름"}
                        """)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── DELETE /api/v1/chat-rooms/{roomId} ──────────────────────────────────

    @Test
    @DisplayName("채팅방 삭제 - 유효한 JWT로 200 반환")
    void deleteChatRoom_withValidJwt_returns200() {
        DeleteChatRoomResponse response = new DeleteChatRoomResponse(ROOM_ID, true);
        when(chatRoomService.deleteChatRoom(CLERK_ID, ROOM_ID)).thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .delete()
                .uri("/api/v1/chat-rooms/{roomId}", ROOM_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.roomId").isEqualTo(ROOM_ID.toString())
                .jsonPath("$.deleted").isEqualTo(true);

        verify(chatRoomService).deleteChatRoom(CLERK_ID, ROOM_ID);
    }

    @Test
    @DisplayName("채팅방 삭제 - JWT 없이 요청 시 401 반환")
    void deleteChatRoom_withoutJwt_returns401() {
        webTestClient
                .delete()
                .uri("/api/v1/chat-rooms/{roomId}", ROOM_ID)
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
