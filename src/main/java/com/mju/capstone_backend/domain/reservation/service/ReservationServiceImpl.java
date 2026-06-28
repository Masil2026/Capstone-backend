package com.mju.capstone_backend.domain.reservation.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mju.capstone_backend.domain.chatroom.repository.ChatRoomRepository;
import com.mju.capstone_backend.domain.itinerary.repository.ItineraryRepository;
import com.mju.capstone_backend.domain.reservation.dto.CancelReservationResponse;
import com.mju.capstone_backend.domain.reservation.dto.ChangeReservationResponse;
import com.mju.capstone_backend.domain.reservation.dto.CreateReservationRequest;
import com.mju.capstone_backend.domain.reservation.dto.CreateReservationResponse;
import com.mju.capstone_backend.domain.reservation.dto.DeleteReservationResponse;
import com.mju.capstone_backend.domain.reservation.dto.GetReservationsResponse;
import com.mju.capstone_backend.domain.reservation.dto.PatchReservationRequest;
import com.mju.capstone_backend.domain.reservation.dto.PatchReservationResponse;
import com.mju.capstone_backend.domain.reservation.entity.Reservation;
import com.mju.capstone_backend.domain.reservation.repository.ReservationRepository;
import com.mju.capstone_backend.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReservationServiceImpl implements ReservationService {

    private static final Set<String> VALID_TYPES = Set.of("flight", "accommodation");
    private static final Set<String> VALID_STATUSES = Set.of("confirmed", "changed", "cancelled");
    private static final Set<String> VALID_PATCH_STATUSES = Set.of("changed", "cancelled");
    private static final Set<String> VALID_BOOKED_BY = Set.of("user", "ai");

    private final UserRepository userRepository;
    private final ItineraryRepository itineraryRepository;
    private final ChatRoomRepository chatRoomRepository;
    private final ReservationRepository reservationRepository;
    private final ObjectMapper objectMapper;

    @Override
    public Mono<GetReservationsResponse> getReservations(String clerkId, String type, String status) {
        return userRepository.existsById(clerkId)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found. Please sign up first.")))
                .flatMap(ignored -> {
                    if (type != null && !VALID_TYPES.contains(type)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "type must be one of: flight, accommodation."));
                    }
                    if (status != null && !VALID_STATUSES.contains(status)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "status must be one of: confirmed, changed, cancelled."));
                    }
                    return reservationRepository.findByClerkIdWithFilters(clerkId, type, status)
                            .map(r -> GetReservationsResponse.ReservationDto.from(r, parseDetail(r)))
                            .collectList()
                            .map(GetReservationsResponse::new);
                })
                .onErrorMap(
                        e -> !(e instanceof ResponseStatusException),
                        e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to get reservations.")
                );
    }

    @Override
    public Mono<CreateReservationResponse> createReservation(String clerkId, CreateReservationRequest request) {
        return userRepository.existsById(clerkId)
                .filter(Boolean::booleanValue)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found. Please sign up first.")))
                .flatMap(ignored -> {
                    if (!VALID_TYPES.contains(request.type())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "type must be one of: flight, accommodation."));
                    }
                    if (!VALID_BOOKED_BY.contains(request.bookedBy())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "bookedBy must be one of: user, ai."));
                    }
                    return itineraryRepository.findById(request.itineraryId())
                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Itinerary not found.")))
                            .flatMap(itinerary ->
                                    chatRoomRepository.findById(itinerary.getRoomId())
                                            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                                    "Related chat room data is missing.")))
                                            .flatMap(chatRoom -> {
                                                if (!chatRoom.getClerkId().equals(clerkId)) {
                                                    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                                            "You do not have permission to create a reservation for this itinerary."));
                                                }
                                                validateDetail(request.type(), request.detail());
                                                String detailJson;
                                                try {
                                                    detailJson = objectMapper.writeValueAsString(request.detail());
                                                } catch (Exception e) {
                                                    return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                                            "Failed to create reservation."));
                                                }
                                                Reservation reservation = Reservation.of(
                                                        request.itineraryId(), request.type(), "confirmed",
                                                        request.bookedBy(), request.bookingUrl(), request.externalRefId(),
                                                        detailJson, request.totalPrice(),
                                                        request.currency() != null ? request.currency() : "KRW",
                                                        request.reservedAt());
                                                return reservationRepository.save(reservation)
                                                        .map(CreateReservationResponse::from);
                                            })
                            );
                })
                .onErrorMap(
                        e -> !(e instanceof ResponseStatusException),
                        e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to create reservation.")
                );
    }

    @Override
    public Mono<PatchReservationResponse> updateReservation(String clerkId, UUID reservationId, PatchReservationRequest request) {
        return Mono.<PatchReservationResponse>defer(() -> {
            if (request.isEmpty()) {
                return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one field must be provided."));
            }
            return reservationRepository.findById(reservationId)
                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found.")))
                    .flatMap(reservation ->
                            itineraryRepository.findById(reservation.getItineraryId())
                                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                            "Related itinerary data is missing.")))
                                    .flatMap(itinerary ->
                                            chatRoomRepository.findById(itinerary.getRoomId())
                                                    .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                                            "Related chat room data is missing.")))
                                                    .flatMap(chatRoom -> {
                                                        if (!chatRoom.getClerkId().equals(clerkId)) {
                                                            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                                                    "You do not have permission to update this reservation."));
                                                        }
                                                        if ("cancelled".equals(reservation.getStatus())) {
                                                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                                    "Cannot update a reservation that has already been cancelled."));
                                                        }
                                                        if (request.status() != null && !VALID_PATCH_STATUSES.contains(request.status())) {
                                                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                                    "status must be one of: changed, cancelled."));
                                                        }
                                                        if ("cancelled".equals(request.status()) && request.cancelledAt() == null) {
                                                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                                    "cancelledAt is required when status is cancelled."));
                                                        }
                                                        if ("changed".equals(request.status())
                                                                && (request.detail() == null || request.reservedAt() == null)) {
                                                            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                                                    "detail, reservedAt are required when status is changed."));
                                                        }
                                                        if (request.detail() != null) {
                                                            validateDetail(reservation.getType(), request.detail());
                                                        }
                                                        String detailJson = null;
                                                        try {
                                                            if (request.detail() != null) {
                                                                detailJson = objectMapper.writeValueAsString(request.detail());
                                                            }
                                                        } catch (Exception e) {
                                                            return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                                                    "Failed to update reservation."));
                                                        }
                                                        reservation.update(request.status(), detailJson, request.totalPrice(),
                                                                request.currency(), request.reservedAt(), request.cancelledAt());
                                                        return reservationRepository.save(reservation)
                                                                .map(saved -> "cancelled".equals(request.status())
                                                                        ? (PatchReservationResponse) CancelReservationResponse.from(saved)
                                                                        : ChangeReservationResponse.from(saved));
                                                    })
                                    )
                    );
        })
        .onErrorMap(
                e -> !(e instanceof ResponseStatusException),
                e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to update reservation.")
        );
    }

    @Override
    public Mono<DeleteReservationResponse> deleteReservation(String clerkId, UUID reservationId) {
        return reservationRepository.findById(reservationId)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Reservation not found.")))
                .flatMap(reservation ->
                        itineraryRepository.findById(reservation.getItineraryId())
                                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                        "Related itinerary data is missing.")))
                                .flatMap(itinerary ->
                                        chatRoomRepository.findById(itinerary.getRoomId())
                                                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                                                        "Related chat room data is missing.")))
                                                .flatMap(chatRoom -> {
                                                    if (!chatRoom.getClerkId().equals(clerkId)) {
                                                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                                                "You do not have permission to delete this reservation."));
                                                    }
                                                    return reservationRepository.delete(reservation)
                                                            .thenReturn(DeleteReservationResponse.of(reservationId));
                                                })
                                )
                )
                .onErrorMap(
                        e -> !(e instanceof ResponseStatusException),
                        e -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to delete reservation.")
                );
    }

    private void validateDetail(String type, Map<String, Object> detail) {
        switch (type) {
            case "flight" -> {
                requireKeys(detail, "flight", "airline", "flight_no", "departure", "arrival", "seat_class", "passengers");
                requireNestedKeys(detail, "flight", "departure", "airport", "datetime");
                requireNestedKeys(detail, "flight", "arrival", "airport", "datetime");
                requirePassengers(detail);
            }
            case "accommodation" -> requireKeys(detail, "accommodation", "hotel_name", "room_type", "check_in", "check_out", "guests");
        }
    }

    private void requireKeys(Map<String, Object> detail, String type, String... keys) {
        for (String key : keys) {
            Object val = detail.get(key);
            if (!detail.containsKey(key) || val == null || (val instanceof String s && s.isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "detail." + key + " is required for type '" + type + "'.");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void requireNestedKeys(Map<String, Object> detail, String type, String parent, String... keys) {
        Object parentVal = detail.get(parent);
        if (!(parentVal instanceof Map)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "detail." + parent + " must be an object for type '" + type + "'.");
        }
        Map<String, Object> nested = (Map<String, Object>) parentVal;
        for (String key : keys) {
            Object val = nested.get(key);
            if (!nested.containsKey(key) || val == null || (val instanceof String s && s.isBlank())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "detail." + parent + "." + key + " is required for type '" + type + "'.");
            }
        }
    }

    private void requirePassengers(Map<String, Object> detail) {
        Object val = detail.get("passengers");
        if (!(val instanceof List<?> list) || list.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "detail.passengers must be a non-empty array for type 'flight'.");
        }
        for (int i = 0; i < list.size(); i++) {
            if (!(list.get(i) instanceof Map<?, ?> passenger)
                    || isBlankValue(passenger.get("name"))
                    || isBlankValue(passenger.get("passport"))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "detail.passengers[" + i + "] must include name and passport.");
            }
        }
    }

    private boolean isBlankValue(Object val) {
        return val == null || (val instanceof String s && s.isBlank());
    }

    private Map<String, Object> parseDetail(Reservation reservation) {
        try {
            return objectMapper.readValue(reservation.getDetail(), new TypeReference<>() {});
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }
}
