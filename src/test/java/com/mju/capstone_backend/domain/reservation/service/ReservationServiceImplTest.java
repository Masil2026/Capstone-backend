package com.mju.capstone_backend.domain.reservation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mju.capstone_backend.domain.chatroom.entity.ChatRoom;
import com.mju.capstone_backend.domain.chatroom.repository.ChatRoomRepository;
import com.mju.capstone_backend.domain.itinerary.entity.Itinerary;
import com.mju.capstone_backend.domain.itinerary.repository.ItineraryRepository;
import com.mju.capstone_backend.domain.reservation.dto.CancelReservationResponse;
import com.mju.capstone_backend.domain.reservation.dto.ChangeReservationResponse;
import com.mju.capstone_backend.domain.reservation.dto.CreateReservationRequest;
import com.mju.capstone_backend.domain.reservation.dto.PatchReservationRequest;
import com.mju.capstone_backend.domain.reservation.entity.Reservation;
import com.mju.capstone_backend.domain.reservation.repository.ReservationRepository;
import com.mju.capstone_backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReservationServiceImpl 단위 테스트")
class ReservationServiceImplTest {

        @Mock
        private UserRepository userRepository;

        @Mock
        private ItineraryRepository itineraryRepository;

        @Mock
        private ChatRoomRepository chatRoomRepository;

        @Mock
        private ReservationRepository reservationRepository;

        @Mock
        private ObjectMapper objectMapper;

        @InjectMocks
        private ReservationServiceImpl reservationService;

        private static final String CLERK_ID = "user_testClerkId";
        private static final UUID RESERVATION_ID = UUID.randomUUID();
        private static final UUID ITINERARY_ID = UUID.randomUUID();
        private static final UUID ROOM_ID = UUID.randomUUID();

        // ─── 사용자 검증 ──────────────────────────────────────────────────────────

        @Test
        @DisplayName("존재하지 않는 사용자 - 404 예외")
        void getReservations_userNotFound_throws404() {
                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(false));

                StepVerifier.create(reservationService.getReservations(CLERK_ID, null, null))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                                .verify();

                verify(reservationRepository, never()).findByClerkIdWithFilters(any(), any(), any());
        }

        // ─── 파라미터 검증 ────────────────────────────────────────────────────────

        @Test
        @DisplayName("잘못된 type 파라미터 - 400 예외")
        void getReservations_invalidType_throws400() {
                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));

                StepVerifier.create(reservationService.getReservations(CLERK_ID, "invalid", null))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                                .verify();
        }

        @Test
        @DisplayName("잘못된 status 파라미터 - 400 예외")
        void getReservations_invalidStatus_throws400() {
                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));

                StepVerifier.create(reservationService.getReservations(CLERK_ID, null, "invalid"))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                                .verify();
        }

        // ─── 정상 조회 ────────────────────────────────────────────────────────────

        @Test
        @DisplayName("필터 없이 조회 - 전체 예약 목록 반환")
        void getReservations_noFilters_returnsAll() throws Exception {
                Reservation mockReservation = buildMockReservation("flight", "confirmed");

                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
                when(reservationRepository.findByClerkIdWithFilters(CLERK_ID, null, null))
                                .thenReturn(Flux.just(mockReservation));
                when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                                .thenReturn(Map.of());

                StepVerifier.create(reservationService.getReservations(CLERK_ID, null, null))
                                .assertNext(response -> {
                                        assertThat(response.reservations()).hasSize(1);
                                        assertThat(response.reservations().get(0).type()).isEqualTo("flight");
                                        assertThat(response.reservations().get(0).status()).isEqualTo("confirmed");
                                })
                                .verifyComplete();
        }

        @Test
        @DisplayName("type 필터 적용 - 리포지토리에 type 전달")
        void getReservations_withTypeFilter_passedToRepository() throws Exception {
                Reservation mockReservation = buildMockReservation("accommodation", "confirmed");

                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
                when(reservationRepository.findByClerkIdWithFilters(CLERK_ID, "accommodation", null))
                                .thenReturn(Flux.just(mockReservation));
                when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                                .thenReturn(Map.of());

                StepVerifier.create(reservationService.getReservations(CLERK_ID, "accommodation", null))
                                .assertNext(response -> assertThat(response.reservations()).hasSize(1))
                                .verifyComplete();

                verify(reservationRepository).findByClerkIdWithFilters(CLERK_ID, "accommodation", null);
        }

        @Test
        @DisplayName("결과 없음 - 빈 목록 반환")
        void getReservations_noResults_returnsEmptyList() {
                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
                when(reservationRepository.findByClerkIdWithFilters(CLERK_ID, null, null))
                                .thenReturn(Flux.empty());

                StepVerifier.create(reservationService.getReservations(CLERK_ID, null, null))
                                .assertNext(response -> assertThat(response.reservations()).isEmpty())
                                .verifyComplete();
        }

        // ─── createReservation: 사용자/파라미터 검증 ──────────────────────────────

        @Test
        @DisplayName("예약 생성 - 존재하지 않는 사용자 - 404 예외")
        void createReservation_userNotFound_throws404() {
                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(false));

                StepVerifier.create(reservationService.createReservation(CLERK_ID, buildCreateRequest("flight", "ai")))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.NOT_FOUND)
                                .verify();

                verify(itineraryRepository, never()).findById(any(UUID.class));
        }

        @Test
        @DisplayName("예약 생성 - 잘못된 type - 400 예외")
        void createReservation_invalidType_throws400() {
                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));

                StepVerifier.create(reservationService.createReservation(CLERK_ID, buildCreateRequest("train", "ai")))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().equals(
                                                                "type must be one of: flight, accommodation."))
                                .verify();
        }

        @Test
        @DisplayName("예약 생성 - 잘못된 bookedBy - 400 예외")
        void createReservation_invalidBookedBy_throws400() {
                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));

                StepVerifier.create(reservationService.createReservation(CLERK_ID, buildCreateRequest("flight", "bot")))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().equals("bookedBy must be one of: user, ai."))
                                .verify();
        }

        // ─── createReservation: 소유권 검증 ──────────────────────────────────────

        @Test
        @DisplayName("예약 생성 - 존재하지 않는 itinerary - 404 예외")
        void createReservation_itineraryNotFound_throws404() {
                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.empty());

                StepVerifier.create(reservationService.createReservation(CLERK_ID, buildCreateRequest("flight", "ai")))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.NOT_FOUND
                                                && rse.getReason().equals("Itinerary not found."))
                                .verify();
        }

        @Test
        @DisplayName("예약 생성 - 다른 사용자의 itinerary - 403 예외")
        void createReservation_notOwner_throws403() {
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom otherChatRoom = mock(ChatRoom.class);
                when(otherChatRoom.getClerkId()).thenReturn("user_otherClerkId");

                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(otherChatRoom));

                StepVerifier.create(reservationService.createReservation(CLERK_ID, buildCreateRequest("flight", "ai")))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.FORBIDDEN)
                                .verify();
        }

        @Test
        @DisplayName("예약 생성 - chatRoom 없음 - 500 예외")
        void createReservation_chatRoomNotFound_throws500() {
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);

                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.empty());

                StepVerifier.create(reservationService.createReservation(CLERK_ID, buildCreateRequest("flight", "ai")))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR
                                                && rse.getReason().equals("Related chat room data is missing."))
                                .verify();
        }

        // ─── createReservation: 정상 생성 ────────────────────────────────────────

        @Test
        @DisplayName("예약 생성 - 정상 요청 - 생성된 예약 반환")
        void createReservation_success_returnsResponse() throws Exception {
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom mockChatRoom = mock(ChatRoom.class);
                when(mockChatRoom.getClerkId()).thenReturn(CLERK_ID);
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getId()).thenReturn(UUID.randomUUID());
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);
                when(mockReservation.getType()).thenReturn("flight");
                when(mockReservation.getStatus()).thenReturn("confirmed");
                when(mockReservation.getCreatedAt()).thenReturn(OffsetDateTime.now());

                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(mockChatRoom));
                when(objectMapper.writeValueAsString(any())).thenReturn("{\"airline\":\"대한항공\"}");
                when(reservationRepository.save(any())).thenReturn(Mono.just(mockReservation));

                StepVerifier.create(reservationService.createReservation(CLERK_ID, buildCreateRequest("flight", "ai")))
                                .assertNext(response -> {
                                        assertThat(response.reservationId()).isNotNull();
                                        assertThat(response.type()).isEqualTo("flight");
                                        assertThat(response.status()).isEqualTo("confirmed");
                                })
                                .verifyComplete();

                verify(reservationRepository).save(any());
        }

        // ─── createReservation: detail 검증 ─────────────────────────────────────

        @Test
        @DisplayName("flight - 필수 detail 필드 누락 - 400 예외")
        void createReservation_flightMissingDetailField_throws400() {
                setupOwnerMocks();
                Map<String, Object> invalidDetail = Map.of("airline", "대한항공", "flight_no", "KE123");
                CreateReservationRequest request = new CreateReservationRequest(
                                ITINERARY_ID, "flight", "ai", null, null, invalidDetail, null, null, null);

                StepVerifier.create(reservationService.createReservation(CLERK_ID, request))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                                .verify();
        }

        @Test
        @DisplayName("flight - passengers 빈 배열 - 400 예외")
        void createReservation_flightEmptyPassengers_throws400() {
                setupOwnerMocks();
                Map<String, Object> invalidDetail = new java.util.HashMap<>(buildValidDetail("flight"));
                invalidDetail.put("passengers", List.of());
                CreateReservationRequest request = new CreateReservationRequest(
                                ITINERARY_ID, "flight", "ai", null, null, invalidDetail, null, null, null);

                StepVerifier.create(reservationService.createReservation(CLERK_ID, request))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().contains("passengers"))
                                .verify();
        }

        @Test
        @DisplayName("flight - passenger에 passport 누락 - 400 예외")
        void createReservation_flightPassengerMissingPassport_throws400() {
                setupOwnerMocks();
                Map<String, Object> invalidDetail = new java.util.HashMap<>(buildValidDetail("flight"));
                invalidDetail.put("passengers", List.of(Map.of("name", "홍길동")));
                CreateReservationRequest request = new CreateReservationRequest(
                                ITINERARY_ID, "flight", "ai", null, null, invalidDetail, null, null, null);

                StepVerifier.create(reservationService.createReservation(CLERK_ID, request))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().contains("passengers[0]"))
                                .verify();
        }

        @Test
        @DisplayName("accommodation - 필수 detail 필드 누락 - 400 예외")
        void createReservation_accommodationMissingDetailField_throws400() {
                setupOwnerMocks();
                Map<String, Object> invalidDetail = Map.of("hotel_name", "롯데호텔");
                CreateReservationRequest request = new CreateReservationRequest(
                                ITINERARY_ID, "accommodation", "user", null, null, invalidDetail, null, null, null);

                StepVerifier.create(reservationService.createReservation(CLERK_ID, request))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                                .verify();
        }

        @Test
        @DisplayName("flight - 중첩 필드 빈 문자열 - 400 예외")
        void createReservation_flightBlankNestedField_throws400() {
                setupOwnerMocks();
                Map<String, Object> invalidDetail = new java.util.HashMap<>(buildValidDetail("flight"));
                invalidDetail.put("arrival", Map.of("airport", "", "datetime", "2026-05-01T11:30:00"));
                CreateReservationRequest request = new CreateReservationRequest(
                                ITINERARY_ID, "flight", "ai", null, null, invalidDetail, null, null, null);

                StepVerifier.create(reservationService.createReservation(CLERK_ID, request))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().contains("arrival.airport"))
                                .verify();
        }

        // ─── updateReservation: 파라미터 검증 ────────────────────────────────────

        @Test
        @DisplayName("예약 수정 - 빈 body - 400 예외")
        void updateReservation_emptyBody_throws400() {
                PatchReservationRequest emptyRequest = new PatchReservationRequest(null, null, null, null, null, null);

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID, emptyRequest))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().equals("At least one field must be provided."))
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - 잘못된 status - 400 예외")
        void updateReservation_invalidStatus_throws400() {
                setupUpdateOwnerMocks("confirmed");
                PatchReservationRequest request = new PatchReservationRequest("invalid", null, null, null, null, null);

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID, request))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().equals(
                                                                "status must be one of: changed, cancelled."))
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - status: confirmed (허용 불가) - 400 예외")
        void updateReservation_confirmedStatus_throws400() {
                setupUpdateOwnerMocks("confirmed");
                PatchReservationRequest request = new PatchReservationRequest("confirmed", null, null, null, null, null);

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID, request))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().equals(
                                                                "status must be one of: changed, cancelled."))
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - status: cancelled인데 cancelledAt 누락 - 400 예외")
        void updateReservation_cancelledWithoutCancelledAt_throws400() {
                setupUpdateOwnerMocks("confirmed");

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID,
                                new PatchReservationRequest("cancelled", null, null, null, null, null)))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().equals("cancelledAt is required when status is cancelled."))
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - status: changed인데 필수 필드 누락 - 400 예외")
        void updateReservation_changedWithoutRequiredFields_throws400() {
                setupUpdateOwnerMocks("confirmed");

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID,
                                new PatchReservationRequest("changed", null, null, null, null, null)))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().equals(
                                                                "detail, reservedAt are required when status is changed."))
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - 이미 취소된 예약 - 400 예외")
        void updateReservation_alreadyCancelled_throws400() {
                setupUpdateOwnerMocks("cancelled");

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID,
                                new PatchReservationRequest("changed", null, null, null, null, null)))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().equals(
                                                                "Cannot update a reservation that has already been cancelled."))
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - 잘못된 detail 구조 - 400 예외")
        void updateReservation_invalidDetail_throws400() {
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);
                when(mockReservation.getType()).thenReturn("flight");
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom mockChatRoom = mock(ChatRoom.class);
                when(mockChatRoom.getClerkId()).thenReturn(CLERK_ID);

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(mockReservation));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(mockChatRoom));

                PatchReservationRequest request = new PatchReservationRequest(
                        null, Map.of("airline", "대한항공"), null, null, null, null);

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID, request))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST)
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - detail.{parent}가 객체 아님 - 400 예외")
        void updateReservation_detailParentNotObject_throws400() {
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);
                when(mockReservation.getStatus()).thenReturn("confirmed");
                when(mockReservation.getType()).thenReturn("flight");
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom mockChatRoom = mock(ChatRoom.class);
                when(mockChatRoom.getClerkId()).thenReturn(CLERK_ID);

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(mockReservation));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(mockChatRoom));

                Map<String, Object> badDetail = new java.util.HashMap<>(buildValidDetail("flight"));
                badDetail.put("departure", "ICN");
                PatchReservationRequest request = new PatchReservationRequest(
                        null, badDetail, null, null, OffsetDateTime.now(), null);

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID, request))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().equals("detail.departure must be an object for type 'flight'."))
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - 중첩 detail 필드 빈 문자열 - 400 예외")
        void updateReservation_blankNestedDetailField_throws400() {
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);
                when(mockReservation.getStatus()).thenReturn("confirmed");
                when(mockReservation.getType()).thenReturn("flight");
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom mockChatRoom = mock(ChatRoom.class);
                when(mockChatRoom.getClerkId()).thenReturn(CLERK_ID);

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(mockReservation));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(mockChatRoom));

                Map<String, Object> badDetail = new java.util.HashMap<>(buildValidDetail("flight"));
                badDetail.put("arrival", Map.of("airport", "", "datetime", "2026-05-01T11:30:00"));
                PatchReservationRequest request = new PatchReservationRequest(
                        null, badDetail, null, null, OffsetDateTime.now(), null);

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID, request))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().equals("detail.arrival.airport is required for type 'flight'."))
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - status: changed인데 reservedAt만 누락 - 400 예외")
        void updateReservation_changedMissingOnlyReservedAt_throws400() {
                setupUpdateOwnerMocks("confirmed");

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID,
                                new PatchReservationRequest("changed", buildValidDetail("flight"), null, null, null, null)))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.BAD_REQUEST
                                                && rse.getReason().equals(
                                                                "detail, reservedAt are required when status is changed."))
                                .verify();
        }

        // ─── updateReservation: 소유권 검증 ──────────────────────────────────────

        @Test
        @DisplayName("예약 수정 - 존재하지 않는 예약 - 404 예외")
        void updateReservation_reservationNotFound_throws404() {
                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.empty());

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID,
                                new PatchReservationRequest("cancelled", null, null, null, null, null)))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.NOT_FOUND
                                                && rse.getReason().equals("Reservation not found."))
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - itinerary 없음 - 500 예외")
        void updateReservation_itineraryNotFound_throws500() {
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(mockReservation));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.empty());

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID,
                                new PatchReservationRequest("cancelled", null, null, null, null, OffsetDateTime.now())))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR
                                                && rse.getReason().equals("Related itinerary data is missing."))
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - chatRoom 없음 - 500 예외")
        void updateReservation_chatRoomNotFound_throws500() {
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(mockReservation));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.empty());

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID,
                                new PatchReservationRequest("cancelled", null, null, null, null, OffsetDateTime.now())))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR
                                                && rse.getReason().equals("Related chat room data is missing."))
                                .verify();
        }

        @Test
        @DisplayName("예약 수정 - 다른 사용자의 예약 - 403 예외")
        void updateReservation_notOwner_throws403() {
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom otherChatRoom = mock(ChatRoom.class);
                when(otherChatRoom.getClerkId()).thenReturn("user_otherClerkId");

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(mockReservation));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(otherChatRoom));

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID,
                                new PatchReservationRequest("cancelled", null, null, null, null, null)))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.FORBIDDEN)
                                .verify();
        }

        // ─── updateReservation: 정상 수정 ────────────────────────────────────────

        @Test
        @DisplayName("예약 취소 - 정상 요청 - CancelReservationResponse 반환")
        void updateReservation_cancel_returnsCancelResponse() {
                OffsetDateTime cancelledAt = OffsetDateTime.now();
                Reservation existing = mock(Reservation.class);
                when(existing.getItineraryId()).thenReturn(ITINERARY_ID);
                when(existing.getStatus()).thenReturn("confirmed");
                Reservation saved = mock(Reservation.class);
                when(saved.getId()).thenReturn(RESERVATION_ID);
                when(saved.getStatus()).thenReturn("cancelled");
                when(saved.getCancelledAt()).thenReturn(cancelledAt);
                when(saved.getUpdatedAt()).thenReturn(OffsetDateTime.now());
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom mockChatRoom = mock(ChatRoom.class);
                when(mockChatRoom.getClerkId()).thenReturn(CLERK_ID);

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(existing));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(mockChatRoom));
                when(reservationRepository.save(any())).thenReturn(Mono.just(saved));

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID,
                                new PatchReservationRequest("cancelled", null, null, null, null, cancelledAt)))
                                .assertNext(response -> {
                                        assertThat(response).isInstanceOf(CancelReservationResponse.class);
                                        CancelReservationResponse r = (CancelReservationResponse) response;
                                        assertThat(r.reservationId()).isEqualTo(RESERVATION_ID);
                                        assertThat(r.status()).isEqualTo("cancelled");
                                        assertThat(r.cancelledAt()).isEqualTo(cancelledAt);
                                        assertThat(r.updatedAt()).isNotNull();
                                })
                                .verifyComplete();

                verify(reservationRepository).save(existing);
        }

        @Test
        @DisplayName("예약 변경 - 정상 요청 - ChangeReservationResponse 반환")
        void updateReservation_change_returnsChangeResponse() throws Exception {
                OffsetDateTime reservedAt = OffsetDateTime.now();
                Reservation existing = mock(Reservation.class);
                when(existing.getItineraryId()).thenReturn(ITINERARY_ID);
                when(existing.getStatus()).thenReturn("confirmed");
                when(existing.getType()).thenReturn("flight");
                Reservation saved = mock(Reservation.class);
                when(saved.getId()).thenReturn(RESERVATION_ID);
                when(saved.getStatus()).thenReturn("changed");
                when(saved.getReservedAt()).thenReturn(reservedAt);
                when(saved.getUpdatedAt()).thenReturn(OffsetDateTime.now());
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom mockChatRoom = mock(ChatRoom.class);
                when(mockChatRoom.getClerkId()).thenReturn(CLERK_ID);

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(existing));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(mockChatRoom));
                when(objectMapper.writeValueAsString(any())).thenReturn("{}");
                when(reservationRepository.save(any())).thenReturn(Mono.just(saved));

                StepVerifier.create(reservationService.updateReservation(CLERK_ID, RESERVATION_ID,
                                new PatchReservationRequest("changed", buildValidDetail("flight"),
                                                new BigDecimal("290000"), "KRW", reservedAt, null)))
                                .assertNext(response -> {
                                        assertThat(response).isInstanceOf(ChangeReservationResponse.class);
                                        ChangeReservationResponse r = (ChangeReservationResponse) response;
                                        assertThat(r.reservationId()).isEqualTo(RESERVATION_ID);
                                        assertThat(r.status()).isEqualTo("changed");
                                        assertThat(r.reservedAt()).isEqualTo(reservedAt);
                                        assertThat(r.updatedAt()).isNotNull();
                                })
                                .verifyComplete();

                verify(reservationRepository).save(existing);
        }

        // ─── deleteReservation ────────────────────────────────────────────────────

        @Test
        @DisplayName("예약 삭제 - 존재하지 않는 예약 - 404 예외")
        void deleteReservation_reservationNotFound_throws404() {
                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.empty());

                StepVerifier.create(reservationService.deleteReservation(CLERK_ID, RESERVATION_ID))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.NOT_FOUND
                                                && rse.getReason().equals("Reservation not found."))
                                .verify();
        }

        @Test
        @DisplayName("예약 삭제 - itinerary 없음 - 500 예외")
        void deleteReservation_itineraryNotFound_throws500() {
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(mockReservation));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.empty());

                StepVerifier.create(reservationService.deleteReservation(CLERK_ID, RESERVATION_ID))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR
                                                && rse.getReason().equals("Related itinerary data is missing."))
                                .verify();

                verify(reservationRepository, never()).delete(any());
        }

        @Test
        @DisplayName("예약 삭제 - chatRoom 없음 - 500 예외")
        void deleteReservation_chatRoomNotFound_throws500() {
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(mockReservation));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.empty());

                StepVerifier.create(reservationService.deleteReservation(CLERK_ID, RESERVATION_ID))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.INTERNAL_SERVER_ERROR
                                                && rse.getReason().equals("Related chat room data is missing."))
                                .verify();

                verify(reservationRepository, never()).delete(any());
        }

        @Test
        @DisplayName("예약 삭제 - 다른 사용자의 예약 - 403 예외")
        void deleteReservation_notOwner_throws403() {
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom otherChatRoom = mock(ChatRoom.class);
                when(otherChatRoom.getClerkId()).thenReturn("user_otherClerkId");

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(mockReservation));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(otherChatRoom));

                StepVerifier.create(reservationService.deleteReservation(CLERK_ID, RESERVATION_ID))
                                .expectErrorMatches(e -> e instanceof ResponseStatusException rse
                                                && rse.getStatusCode() == HttpStatus.FORBIDDEN)
                                .verify();

                verify(reservationRepository, never()).delete(any());
        }

        @Test
        @DisplayName("예약 삭제 - 정상 요청 - reservationId와 deleted:true 반환")
        void deleteReservation_success_returnsResponse() {
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom mockChatRoom = mock(ChatRoom.class);
                when(mockChatRoom.getClerkId()).thenReturn(CLERK_ID);

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(mockReservation));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(mockChatRoom));
                when(reservationRepository.delete(mockReservation)).thenReturn(Mono.empty());

                StepVerifier.create(reservationService.deleteReservation(CLERK_ID, RESERVATION_ID))
                                .assertNext(response -> {
                                        assertThat(response.reservationId()).isEqualTo(RESERVATION_ID);
                                        assertThat(response.deleted()).isTrue();
                                })
                                .verifyComplete();

                verify(reservationRepository).delete(mockReservation);
        }

        // ─── 헬퍼 ────────────────────────────────────────────────────────────────

        private CreateReservationRequest buildCreateRequest(String type, String bookedBy) {
                return new CreateReservationRequest(
                                ITINERARY_ID,
                                type,
                                bookedBy,
                                "https://booking.example.com/flight/123",
                                "KE12345678",
                                buildValidDetail(type),
                                new BigDecimal("320000"),
                                "KRW",
                                OffsetDateTime.now());
        }

        private Map<String, Object> buildValidDetail(String type) {
                return switch (type) {
                        case "flight" -> Map.of(
                                        "airline", "대한항공",
                                        "flight_no", "KE123",
                                        "departure", Map.of("airport", "ICN", "datetime", "2026-05-01T09:00:00"),
                                        "arrival", Map.of("airport", "NRT", "datetime", "2026-05-01T11:30:00"),
                                        "seat_class", "economy",
                                        "passengers", List.of(Map.of("name", "홍길동", "passport", "M12345678")));
                        case "accommodation" -> Map.of(
                                        "hotel_name", "롯데호텔 도쿄",
                                        "room_type", "디럭스 더블",
                                        "check_in", "2026-05-01",
                                        "check_out", "2026-05-03",
                                        "guests", 2);
                        default -> Map.of();
                };
        }

        private void setupUpdateOwnerMocks(String currentStatus) {
                Reservation mockReservation = mock(Reservation.class);
                when(mockReservation.getItineraryId()).thenReturn(ITINERARY_ID);
                when(mockReservation.getStatus()).thenReturn(currentStatus);
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom mockChatRoom = mock(ChatRoom.class);
                when(mockChatRoom.getClerkId()).thenReturn(CLERK_ID);

                when(reservationRepository.findById(RESERVATION_ID)).thenReturn(Mono.just(mockReservation));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(mockChatRoom));
        }

        private void setupOwnerMocks() {
                Itinerary mockItinerary = mock(Itinerary.class);
                when(mockItinerary.getRoomId()).thenReturn(ROOM_ID);
                ChatRoom mockChatRoom = mock(ChatRoom.class);
                when(mockChatRoom.getClerkId()).thenReturn(CLERK_ID);

                when(userRepository.existsById(CLERK_ID)).thenReturn(Mono.just(true));
                when(itineraryRepository.findById(ITINERARY_ID)).thenReturn(Mono.just(mockItinerary));
                when(chatRoomRepository.findById(ROOM_ID)).thenReturn(Mono.just(mockChatRoom));
        }

        private Reservation buildMockReservation(String type, String status) {
                Reservation r = mock(Reservation.class);
                when(r.getId()).thenReturn(UUID.randomUUID());
                when(r.getItineraryId()).thenReturn(UUID.randomUUID());
                when(r.getType()).thenReturn(type);
                when(r.getStatus()).thenReturn(status);
                when(r.getBookedBy()).thenReturn("ai");
                when(r.getBookingUrl()).thenReturn(null);
                when(r.getExternalRefId()).thenReturn("REF-001");
                when(r.getDetail()).thenReturn("{}");
                when(r.getTotalPrice()).thenReturn(BigDecimal.valueOf(350000));
                when(r.getCurrency()).thenReturn("KRW");
                when(r.getReservedAt()).thenReturn(OffsetDateTime.now());
                when(r.getCancelledAt()).thenReturn(null);
                when(r.getCreatedAt()).thenReturn(OffsetDateTime.now());
                when(r.getUpdatedAt()).thenReturn(OffsetDateTime.now());
                return r;
        }
}
