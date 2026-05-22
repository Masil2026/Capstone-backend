package com.mju.capstone_backend.domain.reservation.repository;

import com.mju.capstone_backend.domain.reservation.entity.Reservation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    boolean existsByItineraryId(UUID itineraryId);

    boolean existsByItineraryIdAndStatusIn(UUID itineraryId, List<String> statuses);

    @Modifying
    @Transactional
    @Query("DELETE FROM Reservation r WHERE r.itineraryId = :itineraryId")
    void deleteAllByItineraryId(@Param("itineraryId") UUID itineraryId);

    @Modifying
    @Transactional
    @Query("DELETE FROM Reservation r WHERE r.itineraryId = :itineraryId AND r.status = 'cancelled'")
    void deleteCancelledByItineraryId(@Param("itineraryId") UUID itineraryId);

    @Query(value = """
            SELECT r.* FROM reservations r
            INNER JOIN itineraries i ON r.itinerary_id = i.id
            INNER JOIN chat_rooms cr ON i.room_id = cr.id
            WHERE cr.clerk_id = :clerkId
            AND (:type IS NULL OR r.type = :type)
            AND (:status IS NULL OR r.status = :status)
            ORDER BY r.updated_at DESC
            """, nativeQuery = true)
    List<Reservation> findByClerkIdWithFilters(
            @Param("clerkId") String clerkId,
            @Param("type") String type,
            @Param("status") String status
    );
}
