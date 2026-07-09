package com.mju.capstone_backend.domain.itinerary.repository;

import com.mju.capstone_backend.domain.itinerary.dto.ItinerarySummary;
import com.mju.capstone_backend.domain.itinerary.entity.Itinerary;
import org.springframework.data.r2dbc.repository.Query;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface ItineraryRepository extends ReactiveCrudRepository<Itinerary, UUID> {

    Mono<Itinerary> findByRoomId(UUID roomId);

    @Query("""
            SELECT i.id,
                   c.name,
                   i.status,
                   CAST(i.origin AS text) AS origin,
                   CAST(i.destinations AS text) AS destinations,
                   i.total_days,
                   i.start_date
            FROM itineraries i
            JOIN chat_rooms c ON i.room_id = c.id
            WHERE c.clerk_id = :clerkId
            ORDER BY CASE WHEN i.status = 'draft' THEN 0 ELSE 1 END,
                     i.start_date ASC
            """)
    Flux<ItinerarySummary> findSummariesByClerkId(String clerkId);
}
