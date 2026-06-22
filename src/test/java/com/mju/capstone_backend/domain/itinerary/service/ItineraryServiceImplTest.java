package com.mju.capstone_backend.domain.itinerary.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mju.capstone_backend.domain.chatroom.entity.ChatRoom;
import io.r2dbc.postgresql.codec.Json;
import com.mju.capstone_backend.domain.chatroom.repository.ChatRoomRepository;
import com.mju.capstone_backend.domain.itinerary.dto.DestinationItem;
import com.mju.capstone_backend.domain.itinerary.dto.GetItinerariesResponse;
import com.mju.capstone_backend.domain.itinerary.dto.GetItineraryLogsResponse;
import com.mju.capstone_backend.domain.itinerary.dto.ItinerarySummary;
import com.mju.capstone_backend.domain.itinerary.dto.PatchDayPlansRequest;
import com.mju.capstone_backend.domain.itinerary.dto.PatchItemStatusRequest;
import com.mju.capstone_backend.domain.itinerary.dto.PatchItineraryRequest;
import com.mju.capstone_backend.domain.itinerary.dto.PatchStatusRequest;
import com.mju.capstone_backend.domain.itinerary.entity.Itinerary;
import com.mju.capstone_backend.domain.itinerary.entity.ItineraryLog;
import com.mju.capstone_backend.domain.itinerary.repository.ItineraryLogRepository;
import com.mju.capstone_backend.domain.itinerary.repository.ItineraryRepository;
import com.mju.capstone_backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@ExtendWith(MockitoExtension.class)
@DisplayName("ItineraryServiceImpl 단위 테스트")
class ItineraryServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ItineraryRepository itineraryRepository;

    @Mock
    private ItineraryLogRepository itineraryLogRepository;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @InjectMocks
    private ItineraryServiceImpl itineraryService;

    private static final String CLERK_ID = "user_testClerkId";
    private static final UUID ITINERARY_ID = UUID.randomUUID();
    private static final UUID ROOM_ID = UUID.randomUUID();

    @BeforeEach
    void injectObjectMapper() throws Exception {
        var mapperField = ItineraryServiceImpl.class.getDeclaredField("objectMapper");
        mapperField.setAccessible(true);
        mapperField.set(itineraryService, new ObjectMapper().findAndRegisterModules());
    }

    // ─── getItineraries ───────────────────────────────────────────────────────

    @Test
    @DisplayName("일정 목록 조회 - 정상 요청 시 draft 우선 정렬된 목록 반환")
    void getItineraries_success() {
        ItinerarySummary draft = new ItinerarySummary(UUID.randomUUID(), "도쿄 3박 4일 여행", "draft",
                "도쿄", 4, LocalDate.of(2026, 5, 1));
        ItinerarySummary completed = new ItinerarySummary(UUID.randomUUID(), "부산 2박 3일 여행", "completed",
                "부산", 3, LocalDate.of(2026, 3, 20));

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findSummariesByClerkId(CLERK_ID)).thenReturn(Flux.just(draft, completed));

        StepVerifier.create(itineraryService.getItineraries(CLERK_ID))
                .assertNext(res -> {
                    assertThat(res.itineraries()).hasSize(2);
                    assertThat(res.itineraries().get(0).status()).isEqualTo("draft");
                    assertThat(res.itineraries().get(1).status()).isEqualTo("completed");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("일정 목록 조회 - 일정이 없는 경우 빈 배열 반환")
    void getItineraries_empty() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findSummariesByClerkId(CLERK_ID)).thenReturn(Flux.empty());

        StepVerifier.create(itineraryService.getItineraries(CLERK_ID))
                .assertNext(res -> assertThat(res.itineraries()).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("일정 목록 조회 - 존재하지 않는 사용자는 404 반환")
    void getItineraries_userNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(itineraryService.getItineraries(CLERK_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    // ─── getItinerary ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("일정 상세 조회 - 정상 요청 시 dayPlans에 index 부여 후 반환")
    void getItinerary_success() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID,
                "{\"2026-05-01\":[" +
                "{\"time\":\"12:00 ~ 14:00\",\"plan_name\":\"점심\",\"place\":\"식당\",\"note\":\"\",\"status\":\"todo\"}," +
                "{\"time\":\"09:00 ~ 11:00\",\"plan_name\":\"경복궁\",\"place\":\"경복궁\",\"note\":\"\",\"status\":\"done\"}" +
                "]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 3박 4일 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.getItinerary(CLERK_ID, ITINERARY_ID))
                .assertNext(res -> {
                    assertThat(res.itineraryId()).isEqualTo(ITINERARY_ID);
                    assertThat(res.name()).isEqualTo("서울 3박 4일 여행");
                    var items = res.dayPlans().get("2026-05-01");
                    assertThat(items).hasSize(2);
                    assertThat(items.get(0).get("index")).isEqualTo(0);
                    assertThat(items.get(0).get("plan_name")).isEqualTo("경복궁");
                    assertThat(items.get(1).get("index")).isEqualTo(1);
                    assertThat(items.get(1).get("plan_name")).isEqualTo("점심");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("일정 상세 조회 - 존재하지 않는 사용자는 404 반환")
    void getItinerary_userNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(itineraryService.getItinerary(CLERK_ID, ITINERARY_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("일정 상세 조회 - 존재하지 않는 일정은 404 반환")
    void getItinerary_notFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.empty());

        StepVerifier.create(itineraryService.getItinerary(CLERK_ID, ITINERARY_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("일정 상세 조회 - 다른 사용자의 일정 접근 시 403 반환")
    void getItinerary_otherUser_returns403() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, "user_otherClerkId", "타인의 일정");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.getItinerary(CLERK_ID, ITINERARY_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == FORBIDDEN)
                .verify();
    }

    // ─── getItineraryLogs ─────────────────────────────────────────────────────

    @Test
    @DisplayName("수정 이력 조회 - 이력이 있으면 목록 반환")
    void getItineraryLogs_success() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        ItineraryLog log1 = mockItineraryLog(ITINERARY_ID, BigDecimal.valueOf(500000),
                OffsetDateTime.parse("2026-04-03T20:00:00+00:00"));
        ItineraryLog log2 = mockItineraryLog(ITINERARY_ID, BigDecimal.valueOf(600000),
                OffsetDateTime.parse("2026-04-05T14:30:00+00:00"));

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryLogRepository.findByItineraryIdOrderByCreatedAtDesc(ITINERARY_ID))
                .thenReturn(Flux.just(log2, log1));

        StepVerifier.create(itineraryService.getItineraryLogs(CLERK_ID, ITINERARY_ID))
                .assertNext(res -> {
                    assertThat(res.itineraryId()).isEqualTo(ITINERARY_ID);
                    assertThat(res.logs()).hasSize(2);
                    assertThat(res.logs().get(0).budget()).isEqualByComparingTo(BigDecimal.valueOf(600000));
                    assertThat(res.logs().get(1).budget()).isEqualByComparingTo(BigDecimal.valueOf(500000));
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("수정 이력 조회 - 이력이 없으면 빈 배열 반환")
    void getItineraryLogs_empty() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryLogRepository.findByItineraryIdOrderByCreatedAtDesc(ITINERARY_ID))
                .thenReturn(Flux.empty());

        StepVerifier.create(itineraryService.getItineraryLogs(CLERK_ID, ITINERARY_ID))
                .assertNext(res -> assertThat(res.logs()).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("수정 이력 조회 - 존재하지 않는 사용자는 404 반환")
    void getItineraryLogs_userNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(itineraryService.getItineraryLogs(CLERK_ID, ITINERARY_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("수정 이력 조회 - 존재하지 않는 일정은 404 반환")
    void getItineraryLogs_itineraryNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.empty());

        StepVerifier.create(itineraryService.getItineraryLogs(CLERK_ID, ITINERARY_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("수정 이력 조회 - 다른 사용자의 일정 접근 시 403 반환")
    void getItineraryLogs_otherUser_returns403() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, "user_otherClerkId", "타인의 일정");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.getItineraryLogs(CLERK_ID, ITINERARY_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == FORBIDDEN)
                .verify();
    }

    // ─── patchItinerary ───────────────────────────────────────────────────────

    @Test
    @DisplayName("기본 정보 수정 - 정상 요청 시 수정된 일정 반환")
    void patchItinerary_success() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID,
                "{\"2026-05-01\":[],\"2026-05-02\":[],\"2026-05-03\":[],\"2026-05-04\":[]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");
        PatchItineraryRequest request = new PatchItineraryRequest(
                null, BigDecimal.valueOf(300000), 3, null, null);

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryLogRepository.save(any(ItineraryLog.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID, request))
                .assertNext(res -> {
                    assertThat(res.itineraryId()).isEqualTo(ITINERARY_ID);
                    assertThat(res.budget()).isEqualByComparingTo(BigDecimal.valueOf(300000));
                    assertThat(res.adultCount()).isEqualTo(3);
                })
                .verifyComplete();

        verify(itineraryLogRepository).save(any(ItineraryLog.class));
        verify(itineraryRepository).save(itinerary);
    }

    @Test
    @DisplayName("기본 정보 수정 - 날짜 범위 확대 시 새 날짜 키가 day_plans에 추가됨")
    void patchItinerary_dateRangeExpand_addsDayPlanKeys() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID,
                "{\"2026-05-01\":[],\"2026-05-02\":[],\"2026-05-03\":[],\"2026-05-04\":[]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");
        PatchItineraryRequest request = new PatchItineraryRequest(
                List.of(new DestinationItem("서울", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 5))),
                null, null, null, null);

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryLogRepository.save(any(ItineraryLog.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID, request))
                .assertNext(res -> {
                    assertThat(res.endDate()).isEqualTo(LocalDate.of(2026, 5, 5));
                    assertThat(res.totalDays()).isEqualTo(5);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("기본 정보 수정 - 날짜 범위 축소 시 범위 밖 day_plans 키 제거됨")
    void patchItinerary_dateRangeNarrow_removesDayPlanKeys() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID,
                "{\"2026-05-01\":[],\"2026-05-02\":[],\"2026-05-03\":[],\"2026-05-04\":[]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");
        PatchItineraryRequest request = new PatchItineraryRequest(
                List.of(new DestinationItem("서울", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 2))),
                null, null, null, null);

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryLogRepository.save(any(ItineraryLog.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID, request))
                .assertNext(res -> {
                    assertThat(res.endDate()).isEqualTo(LocalDate.of(2026, 5, 2));
                    assertThat(res.totalDays()).isEqualTo(2);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("기본 정보 수정 - startDate가 endDate보다 늦으면 400 반환")
    void patchItinerary_startAfterEnd_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");
        PatchItineraryRequest request = new PatchItineraryRequest(
                List.of(new DestinationItem("서울", LocalDate.of(2026, 5, 10), LocalDate.of(2026, 5, 1))),
                null, null, null, null);

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID, request))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("기본 정보 수정 - adultCount가 0이면 400 반환")
    void patchItinerary_adultCountZero_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");
        PatchItineraryRequest request = new PatchItineraryRequest(null, null, 0, null, null);

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID, request))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("기본 정보 수정 - childCount만 전달하면 400 반환")
    void patchItinerary_childCountWithoutChildAges_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");
        PatchItineraryRequest request = new PatchItineraryRequest(null, null, null, 1, null);

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID, request))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("기본 정보 수정 - childAges 길이가 childCount와 불일치하면 400 반환")
    void patchItinerary_childAgesMismatch_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");
        PatchItineraryRequest request = new PatchItineraryRequest(null, null, null, 2, List.of(5));

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID, request))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("기본 정보 수정 - 요청 필드가 모두 기존 값과 동일하면 400 반환")
    void patchItinerary_noChanges_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID,
                "{\"2026-05-01\":[],\"2026-05-02\":[],\"2026-05-03\":[],\"2026-05-04\":[]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");
        PatchItineraryRequest request = new PatchItineraryRequest(
                List.of(new DestinationItem("서울", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4))),
                BigDecimal.valueOf(500000), 2, null, null);

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID, request))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && rse.getReason().contains("No changes detected"))
                .verify();
    }

    @Test
    @DisplayName("기본 정보 수정 - 존재하지 않는 사용자는 404 반환")
    void patchItinerary_userNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID,
                        new PatchItineraryRequest(null, null, null, null, null)))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("기본 정보 수정 - 존재하지 않는 일정은 404 반환")
    void patchItinerary_itineraryNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.empty());

        StepVerifier.create(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID,
                        new PatchItineraryRequest(null, null, null, null, null)))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("기본 정보 수정 - 다른 사용자의 일정 수정 시 403 반환")
    void patchItinerary_otherUser_returns403() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, "user_otherClerkId", "타인의 일정");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchItinerary(CLERK_ID, ITINERARY_ID,
                        new PatchItineraryRequest(null, null, null, null, null)))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == FORBIDDEN)
                .verify();
    }

    // ─── patchDayPlans ────────────────────────────────────────────────────────

    @Test
    @DisplayName("day_plans 수정 - 정상 요청 시 병합된 전체 dayPlans 반환")
    void patchDayPlans_success() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID,
                "{\"2026-05-01\":[],\"2026-05-02\":[],\"2026-05-03\":[]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        Map<String, List<Map<String, Object>>> requestDayPlans = Map.of(
                "2026-05-01", List.of(
                        Map.of("plan_name", "경복궁", "time", "09:00 ~ 12:00", "place", "경복궁", "note", "")
                )
        );
        PatchDayPlansRequest request = new PatchDayPlansRequest(requestDayPlans);

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryLogRepository.save(any(ItineraryLog.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID, request))
                .assertNext(res -> {
                    assertThat(res.itineraryId()).isEqualTo(ITINERARY_ID);
                    assertThat(res.dayPlans()).containsKey("2026-05-01");
                    assertThat(res.dayPlans()).containsKey("2026-05-02");
                    assertThat(res.dayPlans()).containsKey("2026-05-03");
                    assertThat(res.dayPlans().get("2026-05-01")).hasSize(1);
                    assertThat(res.dayPlans().get("2026-05-01").get(0).get("plan_name")).isEqualTo("경복궁");
                    assertThat(res.dayPlans().get("2026-05-02")).isEmpty();
                })
                .verifyComplete();

        verify(itineraryLogRepository).save(any(ItineraryLog.class));
        verify(itineraryRepository).save(itinerary);
    }

    @Test
    @DisplayName("day_plans 수정 - 여러 아이템이 time 오름차순으로 정렬되어 저장됨")
    void patchDayPlans_itemsSortedByTime() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{\"2026-05-01\":[]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        Map<String, List<Map<String, Object>>> requestDayPlans = Map.of(
                "2026-05-01", List.of(
                        Map.of("plan_name", "저녁", "time", "18:00 ~ 20:00", "place", "식당"),
                        Map.of("plan_name", "아침", "time", "08:00 ~ 09:00", "place", "카페")
                )
        );

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryLogRepository.save(any(ItineraryLog.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID, new PatchDayPlansRequest(requestDayPlans)))
                .assertNext(res -> {
                    List<Map<String, Object>> items = res.dayPlans().get("2026-05-01");
                    assertThat(items.get(0).get("plan_name")).isEqualTo("아침");
                    assertThat(items.get(1).get("plan_name")).isEqualTo("저녁");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("day_plans 수정 - 빈 배열 전달 시 해당 날짜 아이템 전체 삭제")
    void patchDayPlans_emptyArray_clearsDate() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID,
                "{\"2026-05-01\":[{\"plan_name\":\"경복궁\",\"time\":\"09:00 ~ 12:00\",\"place\":\"경복궁\",\"note\":\"\",\"status\":\"todo\"}]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryLogRepository.save(any(ItineraryLog.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of("2026-05-01", List.of()))))
                .assertNext(res -> assertThat(res.dayPlans().get("2026-05-01")).isEmpty())
                .verifyComplete();
    }

    @Test
    @DisplayName("day_plans 수정 - 일정 범위 밖 날짜는 400 반환")
    void patchDayPlans_dateOutOfRange_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of("2026-05-10", List.of()))))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && rse.getReason().contains("is out of the itinerary date range"))
                .verify();
    }

    @Test
    @DisplayName("day_plans 수정 - 존재하지 않는 날짜(2026-02-31)는 별도 400 반환")
    void patchDayPlans_nonExistentDate_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of("2026-02-31", List.of()))))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && rse.getReason().contains("Invalid date"))
                .verify();
    }

    @Test
    @DisplayName("day_plans 수정 - 필수 필드 누락 시 400 반환")
    void patchDayPlans_missingRequiredField_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of("2026-05-01",
                                List.of(Map.of("plan_name", "경복궁", "time", "09:00 ~ 12:00"))))))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("day_plans 수정 - time 형식 오류 시 400 반환")
    void patchDayPlans_invalidTimeFormat_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of("2026-05-01",
                                List.of(Map.of("plan_name", "경복궁", "time", "9:00~12:00",
                                        "place", "경복궁", "status", "todo"))))))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("day_plans 수정 - 기존 아이템과 time이 일치하면 기존 status 이어받음")
    void patchDayPlans_preservesExistingStatus() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID,
                "{\"2026-05-01\":[" +
                "{\"plan_name\":\"경복궁\",\"time\":\"09:00 ~ 12:00\",\"place\":\"경복궁\",\"note\":\"\",\"status\":\"done\"}," +
                "{\"plan_name\":\"광장시장\",\"time\":\"12:00 ~ 14:00\",\"place\":\"광장시장\",\"note\":\"\",\"status\":\"todo\"}" +
                "]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        PatchDayPlansRequest request = new PatchDayPlansRequest(Map.of(
                "2026-05-01", List.of(
                        Map.of("plan_name", "경복궁 재방문", "time", "09:00 ~ 12:00", "place", "경복궁", "note", ""),
                        Map.of("plan_name", "광장시장", "time", "12:00 ~ 14:00", "place", "광장시장", "note", ""),
                        Map.of("plan_name", "남산타워", "time", "15:00 ~ 17:00", "place", "남산타워", "note", "")
                )
        ));

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryLogRepository.save(any(ItineraryLog.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID, request))
                .assertNext(res -> {
                    List<Map<String, Object>> items = res.dayPlans().get("2026-05-01");
                    assertThat(items).hasSize(3);
                    assertThat(items.get(0).get("plan_name")).isEqualTo("경복궁 재방문");
                    assertThat(items.get(0).get("status")).isEqualTo("done");
                    assertThat(items.get(1).get("status")).isEqualTo("todo");
                    assertThat(items.get(2).get("plan_name")).isEqualTo("남산타워");
                    assertThat(items.get(2).get("status")).isEqualTo("todo");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("day_plans 수정 - 동일 날짜 내 시간 겹침 시 400 반환")
    void patchDayPlans_timeOverlap_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of("2026-05-01", List.of(
                                Map.of("plan_name", "A", "time", "09:00 ~ 13:00", "place", "A"),
                                Map.of("plan_name", "B", "time", "12:00 ~ 14:00", "place", "B")
                        )))))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("day_plans 수정 - 존재하지 않는 사용자는 404 반환")
    void patchDayPlans_userNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of())))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("day_plans 수정 - 존재하지 않는 일정은 404 반환")
    void patchDayPlans_itineraryNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.empty());

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of())))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("day_plans 수정 - 기존 데이터와 동일한 내용 전달 시 400 반환")
    void patchDayPlans_noChanges_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID,
                "{\"2026-05-01\":[{\"plan_name\":\"경복궁\",\"time\":\"09:00 ~ 12:00\",\"place\":\"경복궁\",\"note\":\"\",\"status\":\"todo\"}]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of("2026-05-01",
                                List.of(Map.of("plan_name", "경복궁", "time", "09:00 ~ 12:00",
                                        "place", "경복궁", "note", ""))))))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && rse.getReason().contains("No changes detected"))
                .verify();
    }

    @Test
    @DisplayName("day_plans 수정 - cost 필드가 있는 아이템은 값이 보존됨")
    void patchDayPlans_costFieldPreserved() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{\"2026-05-01\":[]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        Map<String, Object> costObj = Map.of("amount", 450000, "currency", "KRW", "amount_krw", 450000);

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryLogRepository.save(any(ItineraryLog.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of("2026-05-01",
                                List.of(Map.of("plan_name", "항공편", "time", "10:00 ~ 12:30",
                                        "place", "나리타공항", "note", "직항", "cost", costObj))))))
                .assertNext(res -> {
                    var item = res.dayPlans().get("2026-05-01").get(0);
                    assertThat(item.get("cost")).isNotNull();
                    @SuppressWarnings("unchecked")
                    var cost = (Map<String, Object>) item.get("cost");
                    assertThat(cost.get("currency")).isEqualTo("KRW");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("day_plans 수정 - cost 없는 아이템은 cost: null로 정규화됨")
    void patchDayPlans_costNormalizedToNullWhenAbsent() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{\"2026-05-01\":[]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryLogRepository.save(any(ItineraryLog.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of("2026-05-01",
                                List.of(Map.of("plan_name", "경복궁", "time", "09:00 ~ 12:00", "place", "경복궁"))))))
                .assertNext(res -> {
                    var item = res.dayPlans().get("2026-05-01").get(0);
                    assertThat(item).containsKey("cost");
                    assertThat(item.get("cost")).isNull();
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("day_plans 수정 - 다른 사용자의 일정 수정 시 403 반환")
    void patchDayPlans_otherUser_returns403() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, "user_otherClerkId", "타인의 일정");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchDayPlans(CLERK_ID, ITINERARY_ID,
                        new PatchDayPlansRequest(Map.of())))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == FORBIDDEN)
                .verify();
    }

    // ─── patchStatus ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("상태 변경 - 정상 요청 시 변경된 상태 반환")
    void patchStatus_success() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchStatus(CLERK_ID, ITINERARY_ID, new PatchStatusRequest("completed")))
                .assertNext(res -> {
                    assertThat(res.itineraryId()).isEqualTo(ITINERARY_ID);
                    assertThat(res.status()).isEqualTo("completed");
                })
                .verifyComplete();

        verify(itineraryRepository).save(itinerary);
    }

    @Test
    @DisplayName("상태 변경 - 허용되지 않는 status 값이면 400 반환")
    void patchStatus_invalidStatus_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchStatus(CLERK_ID, ITINERARY_ID, new PatchStatusRequest("invalid")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && rse.getReason().contains("status must be one of"))
                .verify();
    }

    @Test
    @DisplayName("상태 변경 - 기존 status와 동일하면 400 반환")
    void patchStatus_noChanges_returns400() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchStatus(CLERK_ID, ITINERARY_ID, new PatchStatusRequest("draft")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && rse.getReason().contains("No changes detected"))
                .verify();
    }

    @Test
    @DisplayName("상태 변경 - 존재하지 않는 사용자는 404 반환")
    void patchStatus_userNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(itineraryService.patchStatus(CLERK_ID, ITINERARY_ID, new PatchStatusRequest("completed")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("상태 변경 - 존재하지 않는 일정은 404 반환")
    void patchStatus_itineraryNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.empty());

        StepVerifier.create(itineraryService.patchStatus(CLERK_ID, ITINERARY_ID, new PatchStatusRequest("completed")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("상태 변경 - 다른 사용자의 일정 수정 시 403 반환")
    void patchStatus_otherUser_returns403() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, "user_otherClerkId", "타인의 일정");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchStatus(CLERK_ID, ITINERARY_ID, new PatchStatusRequest("completed")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == FORBIDDEN)
                .verify();
    }

    // ─── patchItemStatus ─────────────────────────────────────────────────────

    @Test
    @DisplayName("아이템 상태 변경 - 정상 요청 시 변경된 상태 반환")
    void patchItemStatus_success() {
        String dayPlansJson = "{\"2026-05-01\":[" +
                "{\"plan_name\":\"경복궁\",\"time\":\"09:00 ~ 12:00\",\"place\":\"경복궁\",\"note\":\"\",\"status\":\"todo\"}" +
                "]}";
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, dayPlansJson);
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchItemStatus(CLERK_ID, ITINERARY_ID,
                        new PatchItemStatusRequest("2026-05-01", 0, "done")))
                .assertNext(res -> {
                    assertThat(res.itineraryId()).isEqualTo(ITINERARY_ID);
                    assertThat(res.date()).isEqualTo("2026-05-01");
                    assertThat(res.index()).isEqualTo(0);
                    assertThat(res.status()).isEqualTo("done");
                })
                .verifyComplete();

        verify(itineraryRepository).save(itinerary);
    }

    @Test
    @DisplayName("아이템 상태 변경 - 여러 아이템 중 time 오름차순 정렬 기준으로 index 적용")
    void patchItemStatus_correctIndexAfterSort() {
        String dayPlansJson = "{\"2026-05-01\":[" +
                "{\"plan_name\":\"점심\",\"time\":\"12:00 ~ 14:00\",\"place\":\"식당\",\"note\":\"\",\"status\":\"todo\"}," +
                "{\"plan_name\":\"경복궁\",\"time\":\"09:00 ~ 12:00\",\"place\":\"경복궁\",\"note\":\"\",\"status\":\"todo\"}" +
                "]}";
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, dayPlansJson);
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(itineraryRepository.save(any(Itinerary.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(itineraryService.patchItemStatus(CLERK_ID, ITINERARY_ID,
                        new PatchItemStatusRequest("2026-05-01", 0, "done")))
                .assertNext(res -> {
                    assertThat(res.index()).isEqualTo(0);
                    assertThat(res.status()).isEqualTo("done");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("아이템 상태 변경 - 허용되지 않는 status 값이면 400 반환")
    void patchItemStatus_invalidStatus_returns400() {
        String dayPlansJson = "{\"2026-05-01\":[" +
                "{\"plan_name\":\"경복궁\",\"time\":\"09:00 ~ 12:00\",\"place\":\"경복궁\",\"note\":\"\",\"status\":\"todo\"}" +
                "]}";
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, dayPlansJson);
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchItemStatus(CLERK_ID, ITINERARY_ID,
                        new PatchItemStatusRequest("2026-05-01", 0, "pending")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && rse.getReason().contains("Invalid status value"))
                .verify();
    }

    @Test
    @DisplayName("아이템 상태 변경 - 존재하지 않는 날짜면 404 반환")
    void patchItemStatus_dateNotFound_returns404() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{\"2026-05-01\":[]}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchItemStatus(CLERK_ID, ITINERARY_ID,
                        new PatchItemStatusRequest("2026-05-02", 0, "done")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND
                        && rse.getReason().contains("Item not found"))
                .verify();
    }

    @Test
    @DisplayName("아이템 상태 변경 - index가 배열 길이 이상이면 404 반환")
    void patchItemStatus_indexOutOfRange_returns404() {
        String dayPlansJson = "{\"2026-05-01\":[" +
                "{\"plan_name\":\"경복궁\",\"time\":\"09:00 ~ 12:00\",\"place\":\"경복궁\",\"note\":\"\",\"status\":\"todo\"}" +
                "]}";
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, dayPlansJson);
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchItemStatus(CLERK_ID, ITINERARY_ID,
                        new PatchItemStatusRequest("2026-05-01", 5, "done")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND
                        && rse.getReason().contains("Item not found"))
                .verify();
    }

    @Test
    @DisplayName("아이템 상태 변경 - 기존 status와 동일하면 400 반환")
    void patchItemStatus_noChanges_returns400() {
        String dayPlansJson = "{\"2026-05-01\":[" +
                "{\"plan_name\":\"경복궁\",\"time\":\"09:00 ~ 12:00\",\"place\":\"경복궁\",\"note\":\"\",\"status\":\"todo\"}" +
                "]}";
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, dayPlansJson);
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "서울 여행");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchItemStatus(CLERK_ID, ITINERARY_ID,
                        new PatchItemStatusRequest("2026-05-01", 0, "todo")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST
                        && rse.getReason().contains("No changes detected"))
                .verify();
    }

    @Test
    @DisplayName("아이템 상태 변경 - 존재하지 않는 사용자는 404 반환")
    void patchItemStatus_userNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(false));

        StepVerifier.create(itineraryService.patchItemStatus(CLERK_ID, ITINERARY_ID,
                        new PatchItemStatusRequest("2026-05-01", 0, "done")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("아이템 상태 변경 - 존재하지 않는 일정은 404 반환")
    void patchItemStatus_itineraryNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.empty());

        StepVerifier.create(itineraryService.patchItemStatus(CLERK_ID, ITINERARY_ID,
                        new PatchItemStatusRequest("2026-05-01", 0, "done")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("아이템 상태 변경 - 다른 사용자의 일정 수정 시 403 반환")
    void patchItemStatus_otherUser_returns403() {
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID, "{}");
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, "user_otherClerkId", "타인의 일정");

        when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
        when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(itinerary));
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(itineraryService.patchItemStatus(CLERK_ID, ITINERARY_ID,
                        new PatchItemStatusRequest("2026-05-01", 0, "done")))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == FORBIDDEN)
                .verify();
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────────────

    private Itinerary mockItinerary(UUID id, UUID roomId, String dayPlans) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String destinationsJson;
        String childAgesJson;
        try {
            destinationsJson = mapper.writeValueAsString(
                    List.of(new DestinationItem("서울", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4))));
            childAgesJson = mapper.writeValueAsString(List.of(5));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Itinerary itinerary = Itinerary.of(roomId, destinationsJson,
                BigDecimal.valueOf(500000), 2, 1, childAgesJson,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 4));
        try {
            var idField = Itinerary.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(itinerary, id);

            var dayPlansField = Itinerary.class.getDeclaredField("dayPlans");
            dayPlansField.setAccessible(true);
            dayPlansField.set(itinerary, Json.of(dayPlans));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return itinerary;
    }

    private ItineraryLog mockItineraryLog(UUID itineraryId, BigDecimal budget, OffsetDateTime createdAt) {
        Itinerary base = mockItinerary(UUID.randomUUID(), UUID.randomUUID(), "{}");
        try {
            var budgetField = Itinerary.class.getDeclaredField("budget");
            budgetField.setAccessible(true);
            budgetField.set(base, budget);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ItineraryLog log = ItineraryLog.of(base);
        try {
            var idField = ItineraryLog.class.getDeclaredField("itineraryId");
            idField.setAccessible(true);
            idField.set(log, itineraryId);

            var createdAtField = ItineraryLog.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(log, createdAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return log;
    }

    private ChatRoom mockChatRoom(UUID id, String clerkId, String name) {
        ChatRoom chatRoom = ChatRoom.of(clerkId, name);
        try {
            var idField = ChatRoom.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(chatRoom, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return chatRoom;
    }
}
