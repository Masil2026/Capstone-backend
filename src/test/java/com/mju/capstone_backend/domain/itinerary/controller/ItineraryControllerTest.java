package com.mju.capstone_backend.domain.itinerary.controller;

import com.mju.capstone_backend.domain.itinerary.dto.DestinationItem;
import com.mju.capstone_backend.domain.itinerary.dto.GetItinerariesResponse;
import com.mju.capstone_backend.domain.itinerary.dto.GetItineraryLogsResponse;
import com.mju.capstone_backend.domain.itinerary.dto.GetItineraryResponse;
import com.mju.capstone_backend.domain.itinerary.dto.PatchDayPlansRequest;
import com.mju.capstone_backend.domain.itinerary.dto.PatchDayPlansResponse;
import com.mju.capstone_backend.domain.itinerary.dto.PatchItemStatusRequest;
import com.mju.capstone_backend.domain.itinerary.dto.PatchItemStatusResponse;
import com.mju.capstone_backend.domain.itinerary.dto.PatchItineraryRequest;
import com.mju.capstone_backend.domain.itinerary.dto.PatchItineraryResponse;
import com.mju.capstone_backend.domain.itinerary.dto.PatchStatusRequest;
import com.mju.capstone_backend.domain.itinerary.dto.PatchStatusResponse;
import com.mju.capstone_backend.domain.chatmessage.service.ChatMessageService;
import com.mju.capstone_backend.domain.itinerary.service.ItineraryService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(locations = "file:.env")
@DisplayName("ItineraryController 슬라이스 테스트")
class ItineraryControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @MockitoBean
    private ItineraryService itineraryService;

    @MockitoBean
    private ChatMessageService chatMessageService;

    private static final String CLERK_ID = "user_testClerkId";
    private static final UUID ITINERARY_ID = UUID.randomUUID();

    // ─── GET /api/v1/itineraries ──────────────────────────────────────────────

    @Test
    @DisplayName("일정 목록 조회 - 유효한 JWT로 200 반환")
    void getItineraries_withValidJwt_returns200() {
        GetItinerariesResponse response = new GetItinerariesResponse(List.of(
                new GetItinerariesResponse.ItineraryItem(
                        UUID.randomUUID(), "도쿄 3박 4일 여행", "draft",
                        List.of(new DestinationItem("도쿄", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4))),
                        4, LocalDate.of(2026, 5, 1)
                )
        ));
        when(itineraryService.getItineraries(CLERK_ID)).thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .get()
                .uri("/api/v1/itineraries")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.itineraries").isArray()
                .jsonPath("$.itineraries[0].status").isEqualTo("draft")
                .jsonPath("$.itineraries[0].destinations[0].city").isEqualTo("도쿄");

        verify(itineraryService).getItineraries(CLERK_ID);
    }

    @Test
    @DisplayName("일정 목록 조회 - JWT 없이 요청 시 401 반환")
    void getItineraries_withoutJwt_returns401() {
        webTestClient
                .get()
                .uri("/api/v1/itineraries")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── GET /api/v1/itineraries/{itineraryId} ────────────────────────────────

    @Test
    @DisplayName("일정 상세 조회 - 유효한 JWT로 200 반환")
    void getItinerary_withValidJwt_returns200() {
        GetItineraryResponse response = new GetItineraryResponse(
                ITINERARY_ID, "서울 3박 4일 여행", "draft",
                List.of(new DestinationItem("서울", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4))),
                BigDecimal.valueOf(500000), 2, 1, List.of(5),
                4, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4),
                Map.of("2026-05-01", List.of(
                        Map.of("index", 0, "time", "09:00 ~ 11:00", "plan_name", "경복궁",
                                "place", "경복궁", "note", "", "status", "done")
                )),
                OffsetDateTime.now(), OffsetDateTime.now()
        );
        when(itineraryService.getItinerary(CLERK_ID, ITINERARY_ID)).thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .get()
                .uri("/api/v1/itineraries/{itineraryId}", ITINERARY_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.itineraryId").isEqualTo(ITINERARY_ID.toString())
                .jsonPath("$.name").isEqualTo("서울 3박 4일 여행")
                .jsonPath("$.dayPlans['2026-05-01'][0].index").isEqualTo(0);

        verify(itineraryService).getItinerary(CLERK_ID, ITINERARY_ID);
    }

    @Test
    @DisplayName("일정 상세 조회 - JWT 없이 요청 시 401 반환")
    void getItinerary_withoutJwt_returns401() {
        webTestClient
                .get()
                .uri("/api/v1/itineraries/{itineraryId}", ITINERARY_ID)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── GET /api/v1/itineraries/{itineraryId}/logs ───────────────────────────

    @Test
    @DisplayName("수정 이력 조회 - 유효한 JWT로 200 반환")
    void getItineraryLogs_withValidJwt_returns200() {
        GetItineraryLogsResponse response = new GetItineraryLogsResponse(
                ITINERARY_ID,
                List.of(
                        new GetItineraryLogsResponse.LogItem(
                                UUID.randomUUID(),
                                List.of(new DestinationItem("서울", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4))),
                                BigDecimal.valueOf(600000), 2, 1, List.of(5),
                                4, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4),
                                Map.of(), OffsetDateTime.now()
                        ),
                        new GetItineraryLogsResponse.LogItem(
                                UUID.randomUUID(),
                                List.of(new DestinationItem("서울", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4))),
                                BigDecimal.valueOf(500000), 2, 1, List.of(5),
                                4, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4),
                                Map.of(), OffsetDateTime.now().minusDays(2)
                        )
                )
        );
        when(itineraryService.getItineraryLogs(CLERK_ID, ITINERARY_ID)).thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .get()
                .uri("/api/v1/itineraries/{itineraryId}/logs", ITINERARY_ID)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.itineraryId").isEqualTo(ITINERARY_ID.toString())
                .jsonPath("$.logs").isArray()
                .jsonPath("$.logs[0].budget").isEqualTo(600000)
                .jsonPath("$.logs[1].budget").isEqualTo(500000);

        verify(itineraryService).getItineraryLogs(CLERK_ID, ITINERARY_ID);
    }

    @Test
    @DisplayName("수정 이력 조회 - JWT 없이 요청 시 401 반환")
    void getItineraryLogs_withoutJwt_returns401() {
        webTestClient
                .get()
                .uri("/api/v1/itineraries/{itineraryId}/logs", ITINERARY_ID)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── PATCH /api/v1/itineraries/{itineraryId} ──────────────────────────────

    @Test
    @DisplayName("기본 정보 수정 - 유효한 JWT로 200 반환")
    void patchItinerary_withValidJwt_returns200() {
        PatchItineraryResponse response = new PatchItineraryResponse(
                ITINERARY_ID,
                List.of(new DestinationItem("서울", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3))),
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3),
                3, BigDecimal.valueOf(300000),
                2, 1, List.of(7),
                OffsetDateTime.now()
        );
        PatchItineraryRequest request = new PatchItineraryRequest(
                null, BigDecimal.valueOf(300000), null, null, null);

        when(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID, request))
                .thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .patch()
                .uri("/api/v1/itineraries/{itineraryId}", ITINERARY_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.itineraryId").isEqualTo(ITINERARY_ID.toString())
                .jsonPath("$.totalDays").isEqualTo(3)
                .jsonPath("$.destinations[0].city").isEqualTo("서울");

        verify(itineraryService).patchItinerary(CLERK_ID, ITINERARY_ID, request);
    }

    @Test
    @DisplayName("기본 정보 수정 - JWT 없이 요청 시 401 반환")
    void patchItinerary_withoutJwt_returns401() {
        webTestClient
                .patch()
                .uri("/api/v1/itineraries/{itineraryId}", ITINERARY_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── PATCH /api/v1/itineraries/{itineraryId}/day-plans ────────────────────

    @Test
    @DisplayName("day_plans 수정 - 유효한 JWT로 200 반환")
    void patchDayPlans_withValidJwt_returns200() {
        PatchDayPlansResponse response = new PatchDayPlansResponse(
                ITINERARY_ID,
                Map.of("2026-05-01", List.of(
                        Map.of("plan_name", "경복궁", "time", "09:00 ~ 12:00",
                                "place", "경복궁", "note", "", "status", "todo")
                )),
                OffsetDateTime.now()
        );
        PatchDayPlansRequest request = new PatchDayPlansRequest(
                Map.of("2026-05-01", List.of(
                        Map.of("plan_name", "경복궁", "time", "09:00 ~ 12:00",
                                "place", "경복궁", "note", "")
                ))
        );

        when(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID, request))
                .thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .patch()
                .uri("/api/v1/itineraries/{itineraryId}/day-plans", ITINERARY_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.itineraryId").isEqualTo(ITINERARY_ID.toString())
                .jsonPath("$.dayPlans['2026-05-01'][0].plan_name").isEqualTo("경복궁");

        verify(itineraryService).patchDayPlans(CLERK_ID, ITINERARY_ID, request);
    }

    @Test
    @DisplayName("day_plans 수정 - JWT 없이 요청 시 401 반환")
    void patchDayPlans_withoutJwt_returns401() {
        webTestClient
                .patch()
                .uri("/api/v1/itineraries/{itineraryId}/day-plans", ITINERARY_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"dayPlans\":{}}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── PATCH /api/v1/itineraries/{itineraryId}/status ──────────────────────

    @Test
    @DisplayName("상태 변경 - 유효한 JWT로 200 반환")
    void patchStatus_withValidJwt_returns200() {
        PatchStatusResponse response = new PatchStatusResponse(
                ITINERARY_ID, "completed", OffsetDateTime.now());
        PatchStatusRequest request = new PatchStatusRequest("completed");
        when(itineraryService.patchStatus(CLERK_ID, ITINERARY_ID, request))
                .thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .patch()
                .uri("/api/v1/itineraries/{itineraryId}/status", ITINERARY_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.itineraryId").isEqualTo(ITINERARY_ID.toString())
                .jsonPath("$.status").isEqualTo("completed");

        verify(itineraryService).patchStatus(CLERK_ID, ITINERARY_ID, request);
    }

    @Test
    @DisplayName("상태 변경 - JWT 없이 요청 시 401 반환")
    void patchStatus_withoutJwt_returns401() {
        webTestClient
                .patch()
                .uri("/api/v1/itineraries/{itineraryId}/status", ITINERARY_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"status\":\"completed\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ─── PATCH /api/v1/itineraries/{itineraryId}/items/status ────────────────

    @Test
    @DisplayName("아이템 상태 변경 - 유효한 JWT로 200 반환")
    void patchItemStatus_withValidJwt_returns200() {
        PatchItemStatusResponse response = new PatchItemStatusResponse(
                ITINERARY_ID, "2026-05-01", 0, "done", OffsetDateTime.now());
        PatchItemStatusRequest request = new PatchItemStatusRequest("2026-05-01", 0, "done");
        when(itineraryService.patchItemStatus(CLERK_ID, ITINERARY_ID, request))
                .thenReturn(Mono.just(response));

        webTestClient
                .mutateWith(SecurityMockServerConfigurers.mockJwt()
                        .jwt(jwt -> jwt.subject(CLERK_ID)))
                .patch()
                .uri("/api/v1/itineraries/{itineraryId}/items/status", ITINERARY_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.itineraryId").isEqualTo(ITINERARY_ID.toString())
                .jsonPath("$.date").isEqualTo("2026-05-01")
                .jsonPath("$.index").isEqualTo(0)
                .jsonPath("$.status").isEqualTo("done");

        verify(itineraryService).patchItemStatus(CLERK_ID, ITINERARY_ID, request);
    }

    @Test
    @DisplayName("아이템 상태 변경 - JWT 없이 요청 시 401 반환")
    void patchItemStatus_withoutJwt_returns401() {
        webTestClient
                .patch()
                .uri("/api/v1/itineraries/{itineraryId}/items/status", ITINERARY_ID)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue("{\"date\":\"2026-05-01\",\"index\":0,\"status\":\"done\"}")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
