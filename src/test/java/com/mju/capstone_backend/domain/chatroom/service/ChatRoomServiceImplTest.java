package com.mju.capstone_backend.domain.chatroom.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mju.capstone_backend.domain.chatroom.dto.CreateChatRoomRequest;
import com.mju.capstone_backend.domain.itinerary.dto.DestinationItem;
import com.mju.capstone_backend.domain.chatmessage.entity.ChatMessage;
import com.mju.capstone_backend.domain.chatmessage.repository.ChatMessageRepository;
import com.mju.capstone_backend.domain.chatroom.entity.ChatRoom;
import com.mju.capstone_backend.domain.chatroom.repository.ChatRoomRepository;
import com.mju.capstone_backend.domain.itinerary.entity.Itinerary;
import com.mju.capstone_backend.domain.itinerary.repository.ItineraryRepository;
import com.mju.capstone_backend.domain.reservation.repository.ReservationRepository;
import com.mju.capstone_backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpStatus.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ChatRoomServiceImpl 단위 테스트")
class ChatRoomServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @Mock
    private ItineraryRepository itineraryRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private ChatRoomServiceImpl chatRoomService;

    private static final String CLERK_ID = "user_testClerkId";
    private static final UUID ROOM_ID = UUID.randomUUID();
    private static final UUID ITINERARY_ID = UUID.randomUUID();

    @BeforeEach
    void injectScheduler() throws Exception {
        var field = ChatRoomServiceImpl.class.getDeclaredField("dbScheduler");
        field.setAccessible(true);
        field.set(chatRoomService, Schedulers.immediate());

        // transactionTemplate.execute()가 콜백을 실제로 실행하도록 설정
        // lenient: 트랜잭션을 거치지 않는 에러 경로 테스트에서 "unused stubbing" 경고 방지
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> callback = inv.getArgument(0);
            return callback.doInTransaction(null);
        });
    }

    // ─── createChatRoom ───────────────────────────────────────────────────────

    @Test
    @DisplayName("채팅방 생성 - 정상 요청 시 ChatRoom과 Itinerary 저장 후 응답 반환")
    void createChatRoom_success() {
        CreateChatRoomRequest request = new CreateChatRoomRequest(
                List.of(new DestinationItem("도쿄", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3))),
                BigDecimal.valueOf(500000), 2, 0, List.of()
        );

        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "2박 3일 도쿄 여행");
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID);

        when(userRepository.existsById(CLERK_ID)).thenReturn(true);
        when(chatRoomRepository.saveAndFlush(any(ChatRoom.class))).thenReturn(chatRoom);
        when(itineraryRepository.save(any(Itinerary.class))).thenReturn(itinerary);
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(inv -> inv.getArgument(0));

        StepVerifier.create(chatRoomService.createChatRoom(CLERK_ID, request))
                .assertNext(res -> {
                    assertThat(res.roomId()).isEqualTo(ROOM_ID);
                    assertThat(res.itineraryId()).isEqualTo(ITINERARY_ID);
                })
                .verifyComplete();

        verify(chatRoomRepository).saveAndFlush(any(ChatRoom.class));
        verify(itineraryRepository).save(any(Itinerary.class));
        verify(chatMessageRepository).save(any(ChatMessage.class));
    }

    @Test
    @DisplayName("채팅방 생성 - 존재하지 않는 사용자는 404 반환")
    void createChatRoom_userNotFound_returns404() {
        CreateChatRoomRequest request = new CreateChatRoomRequest(
                List.of(new DestinationItem("도쿄", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3))),
                null, 1, 0, List.of()
        );

        when(userRepository.existsById(CLERK_ID)).thenReturn(false);

        StepVerifier.create(chatRoomService.createChatRoom(CLERK_ID, request))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();

        verify(chatRoomRepository, never()).save(any());
        verify(chatRoomRepository, never()).saveAndFlush(any());
        verify(chatMessageRepository, never()).save(any());
    }

    @Test
    @DisplayName("채팅방 생성 - childAges 길이가 childCount와 불일치 시 400 반환")
    void createChatRoom_childAgesMismatch_returns400() {
        CreateChatRoomRequest request = new CreateChatRoomRequest(
                List.of(new DestinationItem("도쿄", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3))),
                null, 1, 2, List.of(5)   // childCount=2 이지만 childAges 길이=1
        );

        when(userRepository.existsById(CLERK_ID)).thenReturn(true);

        StepVerifier.create(chatRoomService.createChatRoom(CLERK_ID, request))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == BAD_REQUEST)
                .verify();
    }

    // ─── getChatRooms ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("채팅방 목록 조회 - 정상 요청 시 목록 반환")
    void getChatRooms_success() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "테스트 채팅방");

        when(userRepository.existsById(CLERK_ID)).thenReturn(true);
        when(chatRoomRepository.findByClerkIdOrderByUpdatedAtDesc(CLERK_ID))
                .thenReturn(List.of(chatRoom));

        StepVerifier.create(chatRoomService.getChatRooms(CLERK_ID))
                .assertNext(res -> assertThat(res.rooms()).hasSize(1))
                .verifyComplete();
    }

    @Test
    @DisplayName("채팅방 목록 조회 - 존재하지 않는 사용자는 404 반환")
    void getChatRooms_userNotFound_returns404() {
        when(userRepository.existsById(CLERK_ID)).thenReturn(false);

        StepVerifier.create(chatRoomService.getChatRooms(CLERK_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    // ─── getChatRoom ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("채팅방 상세 조회 - 정상 요청 시 채팅방 정보 반환")
    void getChatRoom_success() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "테스트 채팅방");
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID);

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(chatRoom));
        when(itineraryRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(itinerary));

        StepVerifier.create(chatRoomService.getChatRoom(CLERK_ID, ROOM_ID))
                .assertNext(res -> {
                    assertThat(res.roomId()).isEqualTo(ROOM_ID);
                    assertThat(res.itineraryId()).isEqualTo(ITINERARY_ID);
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("채팅방 상세 조회 - 존재하지 않는 채팅방은 404 반환")
    void getChatRoom_notFound_returns404() {
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

        StepVerifier.create(chatRoomService.getChatRoom(CLERK_ID, ROOM_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("채팅방 상세 조회 - 다른 사용자의 채팅방 접근 시 403 반환")
    void getChatRoom_otherUser_returns403() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, "user_otherClerkId", "타인의 채팅방");

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(chatRoom));

        StepVerifier.create(chatRoomService.getChatRoom(CLERK_ID, ROOM_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == FORBIDDEN)
                .verify();
    }

    // ─── updateChatRoomName ───────────────────────────────────────────────────

    @Test
    @DisplayName("채팅방 이름 수정 - 정상 요청 시 이름 수정 후 응답 반환")
    void updateChatRoomName_success() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "기존 이름");

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(chatRoom));
        when(chatRoomRepository.save(chatRoom)).thenReturn(chatRoom);

        StepVerifier.create(chatRoomService.updateChatRoomName(CLERK_ID, ROOM_ID, "새 이름"))
                .assertNext(res -> assertThat(res.roomId()).isEqualTo(ROOM_ID))
                .verifyComplete();

        verify(chatRoomRepository).save(chatRoom);
    }

    @Test
    @DisplayName("채팅방 이름 수정 - 존재하지 않는 채팅방은 404 반환")
    void updateChatRoomName_notFound_returns404() {
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

        StepVerifier.create(chatRoomService.updateChatRoomName(CLERK_ID, ROOM_ID, "새 이름"))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("채팅방 이름 수정 - 다른 사용자의 채팅방 수정 시 403 반환")
    void updateChatRoomName_otherUser_returns403() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, "user_otherClerkId", "타인의 채팅방");

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(chatRoom));

        StepVerifier.create(chatRoomService.updateChatRoomName(CLERK_ID, ROOM_ID, "새 이름"))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == FORBIDDEN)
                .verify();
    }

    // ─── deleteChatRoom ───────────────────────────────────────────────────────

    private static final List<String> ACTIVE_STATUSES = List.of("confirmed", "changed");

    @Test
    @DisplayName("채팅방 삭제 - 예약 없음: 트랜잭션 내 채팅방 삭제")
    void deleteChatRoom_noReservations_success() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "삭제할 채팅방");
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID);

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(chatRoom));
        when(itineraryRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(itinerary));
        when(reservationRepository.existsByItineraryIdAndStatusIn(ITINERARY_ID, ACTIVE_STATUSES)).thenReturn(false);
        doNothing().when(reservationRepository).deleteCancelledByItineraryId(ITINERARY_ID);
        doNothing().when(chatRoomRepository).deleteById(ROOM_ID);

        StepVerifier.create(chatRoomService.deleteChatRoom(CLERK_ID, ROOM_ID))
                .assertNext(res -> {
                    assertThat(res.roomId()).isEqualTo(ROOM_ID);
                    assertThat(res.deleted()).isTrue();
                })
                .verifyComplete();

        verify(reservationRepository).deleteCancelledByItineraryId(ITINERARY_ID);
        verify(chatRoomRepository).deleteById(ROOM_ID);
    }

    @Test
    @DisplayName("채팅방 삭제 - cancelled 예약만 존재: 취소 예약 삭제 후 채팅방 삭제")
    void deleteChatRoom_onlyCancelledReservations_deletesAndSucceeds() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "취소 예약만 있는 채팅방");
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID);

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(chatRoom));
        when(itineraryRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(itinerary));
        when(reservationRepository.existsByItineraryIdAndStatusIn(ITINERARY_ID, ACTIVE_STATUSES)).thenReturn(false);
        doNothing().when(reservationRepository).deleteCancelledByItineraryId(ITINERARY_ID);
        doNothing().when(chatRoomRepository).deleteById(ROOM_ID);

        StepVerifier.create(chatRoomService.deleteChatRoom(CLERK_ID, ROOM_ID))
                .assertNext(res -> assertThat(res.deleted()).isTrue())
                .verifyComplete();

        var inOrder = inOrder(reservationRepository, chatRoomRepository);
        inOrder.verify(reservationRepository).deleteCancelledByItineraryId(ITINERARY_ID);
        inOrder.verify(chatRoomRepository).deleteById(ROOM_ID);
    }

    @Test
    @DisplayName("채팅방 삭제 - confirmed/changed 예약 존재 시 409 반환")
    void deleteChatRoom_hasActiveReservations_returns409() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, CLERK_ID, "활성 예약 있는 채팅방");
        Itinerary itinerary = mockItinerary(ITINERARY_ID, ROOM_ID);

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(chatRoom));
        when(itineraryRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(itinerary));
        when(reservationRepository.existsByItineraryIdAndStatusIn(ITINERARY_ID, ACTIVE_STATUSES)).thenReturn(true);

        StepVerifier.create(chatRoomService.deleteChatRoom(CLERK_ID, ROOM_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == CONFLICT)
                .verify();

        verify(chatRoomRepository, never()).deleteById(any());
        verify(reservationRepository, never()).deleteCancelledByItineraryId(any());
    }

    @Test
    @DisplayName("채팅방 삭제 - 존재하지 않는 채팅방은 404 반환")
    void deleteChatRoom_notFound_returns404() {
        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.empty());

        StepVerifier.create(chatRoomService.deleteChatRoom(CLERK_ID, ROOM_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == NOT_FOUND)
                .verify();
    }

    @Test
    @DisplayName("채팅방 삭제 - 다른 사용자의 채팅방 삭제 시 403 반환")
    void deleteChatRoom_otherUser_returns403() {
        ChatRoom chatRoom = mockChatRoom(ROOM_ID, "user_otherClerkId", "타인의 채팅방");

        when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Optional.of(chatRoom));

        StepVerifier.create(chatRoomService.deleteChatRoom(CLERK_ID, ROOM_ID))
                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                        && rse.getStatusCode() == FORBIDDEN)
                .verify();
    }

    // ─── 헬퍼 ─────────────────────────────────────────────────────────────────

    private ChatRoom mockChatRoom(UUID id, String clerkId, String name) {
        ChatRoom chatRoom = ChatRoom.of(clerkId, name);
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

    private Itinerary mockItinerary(UUID id, UUID roomId) {
        Itinerary itinerary = Itinerary.of(
                roomId,
                List.of(new DestinationItem("도쿄", LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 3))),
                null, 1, 0, List.of()
        );
        try {
            var idField = Itinerary.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(itinerary, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return itinerary;
    }
}
