package com.mju.capstone_backend.domain.reservation.repository;

import com.mju.capstone_backend.domain.reservation.entity.Reservation;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ReservationRepository extends ReactiveCrudRepository<Reservation, UUID> {

    Mono<Boolean> existsByItineraryId(UUID itineraryId);

    @Query("SELECT EXISTS(SELECT 1 FROM reservations WHERE itinerary_id = :itineraryId AND status IN ('confirmed', 'changed'))")
    Mono<Boolean> existsActiveByItineraryId(UUID itineraryId);

    @Query("DELETE FROM reservations WHERE itinerary_id = :itineraryId")
    Mono<Void> deleteAllByItineraryId(UUID itineraryId);

    @Query("DELETE FROM reservations WHERE itinerary_id = :itineraryId AND status = 'cancelled'")
    Mono<Void> deleteCancelledByItineraryId(UUID itineraryId);

    @Query("""
            SELECT r.* FROM reservations r
            INNER JOIN itineraries i ON r.itinerary_id = i.id
            INNER JOIN chat_rooms cr ON i.room_id = cr.id
            WHERE cr.clerk_id = :clerkId
            AND (:type IS NULL OR r.type = :type)
            AND (:status IS NULL OR r.status = :status)
            ORDER BY r.updated_at DESC
            """)
    Flux<Reservation> findByClerkIdWithFilters(String clerkId, String type, String status);
}
