package com.mju.capstone_backend.domain.chatmessage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mju.capstone_backend.domain.chatmessage.dto.ChatStreamEvent;
import com.mju.capstone_backend.domain.chatmessage.dto.FastApiDonePayload;
import com.mju.capstone_backend.domain.chatmessage.entity.ChatMessage;
import com.mju.capstone_backend.domain.chatmessage.repository.ChatMessageRepository;
import com.mju.capstone_backend.domain.chatroom.entity.ChatRoom;
import com.mju.capstone_backend.domain.chatroom.repository.ChatRoomRepository;
import com.mju.capstone_backend.domain.itinerary.dto.DestinationItem;
import com.mju.capstone_backend.domain.itinerary.entity.Itinerary;
import com.mju.capstone_backend.domain.itinerary.repository.ItineraryLogRepository;
import com.mju.capstone_backend.domain.itinerary.repository.ItineraryRepository;
import com.mju.capstone_backend.domain.itinerary.service.ItineraryServiceImpl;
import com.mju.capstone_backend.domain.reservation.entity.Reservation;
import com.mju.capstone_backend.domain.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.reactive.TransactionalOperator;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatMessageServiceImpl 단위 테스트")
class ChatMessageServiceImplTest {

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ItineraryRepository itineraryRepository;

    @Mock
    private ItineraryLogRepository itineraryLogRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private FastApiChatClient fastApiChatClient;

    @Mock
    private TransactionalOperator transactionalOperator;

    @Mock
    private ItineraryServiceImpl itineraryServiceImpl;

    @InjectMocks
    private ChatMessageServiceImpl chatMessageService;

    private static final String CLERK_ID = "user_testClerkId";
    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID MSG_ID_1 = UUID.randomUUID();
    private static final UUID MSG_ID_2 = UUID.randomUUID();

    @BeforeEach
    void injectObjectMapper() throws Exception {
        var mapperField = ChatMessageServiceImpl.class.getDeclaredField("objectMapper");
        mapperField.setAccessible(true);
        mapperField.set(chatMessageService, new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }

    // ─── 성공 케이스 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("메시지 조회 - cursor 없이 첫 페이지 조회")
    void getMessages_withoutCursor_returnsFirstPage() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID);
        OffsetDateTime now = OffsetDateTime.now();
        List<ChatMessage> messages = List.of(
                mockChatMessage(MSG_ID_1, ROOM_ID, "user", "안녕하세요", now),
                mockChatMessage(MSG_ID_2, ROOM_ID, "assistant", "네 안녕하세요", now.minusSeconds(30))
        );

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(chatMessageRepository.findByRoomIdOrderByCreatedAtDesc(eq(ROOM_ID), anyInt()))
                .thenReturn(Flux.fromIterable(messages));

        StepVerifier.create(chatMessageService.getMessages(CLERK_ID, ROOM_ID, null, 30))
                .assertNext(res -> {
                    assertThat(res.roomId()).isEqualTo(ROOM_ID);
                    assertThat(res.messages()).hasSize(2);
                    assertThat(res.hasMore()).isFalse();
                    assertThat(res.nextCursor()).isNull();
                })
                .verifyComplete();

        verify(chatMessageRepository).findByRoomIdOrderByCreatedAtDesc(eq(ROOM_ID), anyInt());
        verify(chatMessageRepository, never())
                .findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc(any(UUID.class), any(OffsetDateTime.class), anyInt());
    }

    @Test
    @DisplayName("메시지 조회 - cursor 기반 이전 메시지 조회")
    void getMessages_withCursor_returnsPreviousMessages() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID);
        OffsetDateTime cursor = OffsetDateTime.now().minusHours(1);
        List<ChatMessage> messages = List.of(
                mockChatMessage(MSG_ID_1, ROOM_ID, "user", "이전 메시지", cursor.minusSeconds(30))
        );

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(chatMessageRepository.findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc(
                eq(ROOM_ID), eq(cursor), anyInt()))
                .thenReturn(Flux.fromIterable(messages));

        StepVerifier.create(chatMessageService.getMessages(CLERK_ID, ROOM_ID, cursor, 30))
                .assertNext(res -> {
                    assertThat(res.messages()).hasSize(1);
                    assertThat(res.hasMore()).isFalse();
                })
                .verifyComplete();

        verify(chatMessageRepository)
                .findByRoomIdAndCreatedAtBeforeOrderByCreatedAtDesc(eq(ROOM_ID), eq(cursor), anyInt());
        verify(chatMessageRepository, never()).findByRoomIdOrderByCreatedAtDesc(any(UUID.class), anyInt());
    }

    @Test
    @DisplayName("메시지 조회 - hasMore=true이면 nextCursor 반환")
    void getMessages_hasMore_true_nextCursorReturned() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID);
        OffsetDateTime t1 = OffsetDateTime.now();
        OffsetDateTime t2 = t1.minusSeconds(10);
        OffsetDateTime t3 = t1.minusSeconds(20);

        // limit=2, fetched=3(limit+1) → hasMore=true, page=[msg1, msg2], nextCursor=t2
        List<ChatMessage> fetched = List.of(
                mockChatMessage(MSG_ID_1, ROOM_ID, "user", "첫 번째", t1),
                mockChatMessage(MSG_ID_2, ROOM_ID, "assistant", "두 번째", t2),
                mockChatMessage(UUID.randomUUID(), ROOM_ID, "user", "세 번째(extra)", t3)
        );

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(chatMessageRepository.findByRoomIdOrderByCreatedAtDesc(eq(ROOM_ID), anyInt()))
                .thenReturn(Flux.fromIterable(fetched));

        StepVerifier.create(chatMessageService.getMessages(CLERK_ID, ROOM_ID, null, 2))
                .assertNext(res -> {
                    assertThat(res.messages()).hasSize(2);
                    assertThat(res.hasMore()).isTrue();
                    assertThat(res.nextCursor()).isEqualTo(t2);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("메시지 조회 - 마지막 페이지이면 nextCursor=null 반환")
    void getMessages_lastPage_nextCursorIsNull() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID);

        // limit=5, fetched=1(< limit) → hasMore=false
        List<ChatMessage> fetched = List.of(
                mockChatMessage(MSG_ID_1, ROOM_ID, "user", "마지막 메시지", OffsetDateTime.now())
        );

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(chatMessageRepository.findByRoomIdOrderByCreatedAtDesc(eq(ROOM_ID), anyInt()))
                .thenReturn(Flux.fromIterable(fetched));

        StepVerifier.create(chatMessageService.getMessages(CLERK_ID, ROOM_ID, null, 5))
                .assertNext(res -> {
                    assertThat(res.hasMore()).isFalse();
                    assertThat(res.nextCursor()).isNull();
                })
                .verifyComplete();
    }

    // ─── 예외 케이스 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("메시지 조회 - 존재하지 않는 채팅방은 404 반환")
    void getMessages_roomNotFound_returns404() {
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.empty());

        StepVerifier.create(chatMessageService.getMessages(CLERK_ID, ROOM_ID, null, 30))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("메시지 조회 - 채팅방 소유자가 아니면 403 반환")
    void getMessages_otherUser_returns403() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, "user_otherClerkId");
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(chatMessageService.getMessages(CLERK_ID, ROOM_ID, null, 30))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == FORBIDDEN)
                .verify();

        verify(chatMessageRepository, never()).findByRoomIdOrderByCreatedAtDesc(any(UUID.class), anyInt());
    }

    @Test
    @DisplayName("메시지 조회 - limit이 1 미만이면 400 반환")
    void getMessages_limitTooLow_returns400() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID);
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(chatMessageService.getMessages(CLERK_ID, ROOM_ID, null, 0))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST)
                .verify();
    }

    @Test
    @DisplayName("메시지 조회 - limit이 100 초과이면 400 반환")
    void getMessages_limitTooHigh_returns400() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID);
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));

        StepVerifier.create(chatMessageService.getMessages(CLERK_ID, ROOM_ID, null, 101))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST)
                .verify();
    }

    // ─── reservation / cancel ────────────────────────────────────────────────

    @Test
    @DisplayName("sendMessage - reservation 타입: reservations 저장 후 ReservationResult 반환")
    void sendMessage_reservationType_savesReservationAndReturnsResult() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID);
        Itinerary itinerary = mockItinerary(UUID.randomUUID(), ROOM_ID);
        UUID reservationId = UUID.randomUUID();

        FastApiDonePayload payload = new FastApiDonePayload.Reservation(
                new FastApiDonePayload.MessagePayload("호텔 예약해줘", null),
                new FastApiDonePayload.MessagePayload("예시호텔을 예약했습니다.", null),
                null,
                new FastApiDonePayload.ReservationData(
                        "hotel",
                        "https://booking.tripai.app/stays/HTL-20260511-1A2B3C",
                        "HTL-20260511-1A2B3C",
                        Map.of("name", "예시호텔", "check_in", "2026-05-01", "check_out", "2026-05-03"),
                        new BigDecimal("240000.00"),
                        "KRW",
                        OffsetDateTime.parse("2026-05-11T09:00:00+09:00")
                )
        );

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(fastApiChatClient.stream(eq(ROOM_ID), any()))
                .thenReturn(Flux.just(new ChatStreamEvent.Done(payload)));
        when(chatMessageRepository.save(any())).thenAnswer(inv ->
                Mono.just(mockChatMessageWithId(inv.getArgument(0))));
        when(itineraryRepository.findByRoomId(ROOM_ID)).thenReturn(Mono.just(itinerary));
        when(reservationRepository.save(any())).thenAnswer(inv ->
                Mono.just(mockReservationWithId(inv.getArgument(0), reservationId)));

        StepVerifier.create(
                chatMessageService.sendMessage(CLERK_ID, ROOM_ID, "호텔 예약해줘")
                        .filter(sse -> "done".equals(sse.event()))
        )
                .assertNext(sse -> {
                    String json = (String) sse.data();
                    assertThat(json).contains("reservation");
                    assertThat(json).contains("HTL-20260511-1A2B3C");
                    assertThat(json).contains("confirmed");
                })
                .verifyComplete();

        verify(reservationRepository).save(any(Reservation.class));
    }

    @Test
    @DisplayName("sendMessage - reservation 타입: itinerary 없으면 done 이벤트 오류")
    void sendMessage_reservationType_itineraryNotFound_returnsError() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID);

        FastApiDonePayload payload = new FastApiDonePayload.Reservation(
                new FastApiDonePayload.MessagePayload("항공권 예약해줘", null),
                new FastApiDonePayload.MessagePayload("예약했습니다.", null),
                null,
                new FastApiDonePayload.ReservationData(
                        "flight", "https://booking.tripai.app/flights/KE123", "KE-001",
                        Map.of("airline", "대한항공"), new BigDecimal("320000.00"), "KRW",
                        OffsetDateTime.now()
                )
        );

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(fastApiChatClient.stream(eq(ROOM_ID), any()))
                .thenReturn(Flux.just(new ChatStreamEvent.Done(payload)));
        when(chatMessageRepository.save(any())).thenAnswer(inv ->
                Mono.just(mockChatMessageWithId(inv.getArgument(0))));
        when(itineraryRepository.findByRoomId(ROOM_ID)).thenReturn(Mono.empty());

        StepVerifier.create(chatMessageService.sendMessage(CLERK_ID, ROOM_ID, "항공권 예약해줘"))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("error");
                    assertThat(sse.data()).isInstanceOf(Map.class);
                    assertThat(((Map<?, ?>) sse.data()).get("status")).isEqualTo(404);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("sendMessage - cancel 타입: reservation 상태를 cancelled로 변경하고 CancelResult 반환")
    void sendMessage_cancelType_updatesReservationStatusAndReturnsCancelResult() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID);
        UUID reservationId = UUID.randomUUID();
        OffsetDateTime cancelledAt = OffsetDateTime.parse("2026-04-10T10:00:00+09:00");

        Reservation reservation = mockReservation(reservationId, UUID.randomUUID(), "confirmed");

        FastApiDonePayload payload = new FastApiDonePayload.Cancel(
                new FastApiDonePayload.MessagePayload("예약 취소해줘", null),
                new FastApiDonePayload.MessagePayload("예약을 취소했습니다.", null),
                null,
                new FastApiDonePayload.CancelData(reservationId, cancelledAt)
        );

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(fastApiChatClient.stream(eq(ROOM_ID), any()))
                .thenReturn(Flux.just(new ChatStreamEvent.Done(payload)));
        when(chatMessageRepository.save(any())).thenAnswer(inv ->
                Mono.just(mockChatMessageWithId(inv.getArgument(0))));
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.just(reservation));
        when(reservationRepository.save(any())).thenReturn(Mono.just(reservation));

        StepVerifier.create(
                chatMessageService.sendMessage(CLERK_ID, ROOM_ID, "예약 취소해줘")
                        .filter(sse -> "done".equals(sse.event()))
        )
                .assertNext(sse -> {
                    String json = (String) sse.data();
                    assertThat(json).contains("cancel");
                    assertThat(json).contains(reservationId.toString());
                    assertThat(json).contains("cancelled");
                })
                .verifyComplete();

        verify(reservationRepository).findById(reservationId);
        verify(reservationRepository).save(reservation);
    }

    @Test
    @DisplayName("sendMessage - cancel 타입: reservation 없으면 done 이벤트 오류")
    void sendMessage_cancelType_reservationNotFound_returnsError() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID);
        UUID reservationId = UUID.randomUUID();

        FastApiDonePayload payload = new FastApiDonePayload.Cancel(
                new FastApiDonePayload.MessagePayload("예약 취소해줘", null),
                new FastApiDonePayload.MessagePayload("예약을 취소했습니다.", null),
                null,
                new FastApiDonePayload.CancelData(reservationId, OffsetDateTime.now())
        );

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(chatRoom));
        when(fastApiChatClient.stream(eq(ROOM_ID), any()))
                .thenReturn(Flux.just(new ChatStreamEvent.Done(payload)));
        when(chatMessageRepository.save(any())).thenAnswer(inv ->
                Mono.just(mockChatMessageWithId(inv.getArgument(0))));
        when(reservationRepository.findById(reservationId)).thenReturn(Mono.empty());

        StepVerifier.create(chatMessageService.sendMessage(CLERK_ID, ROOM_ID, "예약 취소해줘"))
                .assertNext(sse -> {
                    assertThat(sse.event()).isEqualTo("error");
                    assertThat(sse.data()).isInstanceOf(Map.class);
                    assertThat(((Map<?, ?>) sse.data()).get("status")).isEqualTo(404);
                })
                .verifyComplete();
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────────────

    private ChatRoom mockChatRoom(UUID id, String clerkId) {
        ChatRoom chatRoom = ChatRoom.of(clerkId, "테스트 채팅방");
        try {
            var idField = ChatRoom.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(chatRoom, id);

            var createdAtField = ChatRoom.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(chatRoom, OffsetDateTime.now());

            var updatedAtField = ChatRoom.class.getDeclaredField("updatedAt");
            updatedAtField.setAccessible(true);
            updatedAtField.set(chatRoom, OffsetDateTime.now());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return chatRoom;
    }

    private ChatMessage mockChatMessage(UUID id, UUID roomId, String role, String content, OffsetDateTime createdAt) {
        ChatMessage msg = ChatMessage.of(roomId, role, content);
        try {
            var idField = ChatMessage.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(msg, id);

            var createdAtField = ChatMessage.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(msg, createdAt);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return msg;
    }

    private ChatMessage mockChatMessageWithId(ChatMessage msg) {
        try {
            var idField = ChatMessage.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(msg, UUID.randomUUID());

            var createdAtField = ChatMessage.class.getDeclaredField("createdAt");
            createdAtField.setAccessible(true);
            createdAtField.set(msg, OffsetDateTime.now());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return msg;
    }

    private Itinerary mockItinerary(UUID id, UUID roomId) {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        String destinationsJson;
        String childAgesJson;
        try {
            destinationsJson = mapper.writeValueAsString(
                    List.of(new DestinationItem("도쿄", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3))));
            childAgesJson = mapper.writeValueAsString(List.of());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        Itinerary itinerary = Itinerary.of(
                roomId, destinationsJson, new BigDecimal("500000"), 2, 0, childAgesJson,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3));
        try {
            var idField = Itinerary.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(itinerary, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return itinerary;
    }

    private Reservation mockReservation(UUID id, UUID itineraryId, String status) {
        Reservation reservation = Reservation.of(
                itineraryId, "hotel", status, "ai",
                "https://booking.tripai.app/stays/HTL-001", "HTL-001",
                "{\"name\":\"예시호텔\"}",
                new BigDecimal("240000"), "KRW",
                OffsetDateTime.parse("2026-05-11T09:00:00+09:00")
        );
        try {
            var idField = Reservation.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(reservation, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return reservation;
    }

    private Reservation mockReservationWithId(Reservation reservation, UUID id) {
        try {
            var idField = Reservation.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(reservation, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return reservation;
    }
}
