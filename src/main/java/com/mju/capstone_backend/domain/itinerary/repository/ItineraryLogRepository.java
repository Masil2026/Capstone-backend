package com.mju.capstone_backend.domain.itinerary.repository;

import com.mju.capstone_backend.domain.itinerary.entity.ItineraryLog;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface ItineraryLogRepository extends ReactiveCrudRepository<ItineraryLog, UUID> {

    Flux<ItineraryLog> findByItineraryIdOrderByCreatedAtDesc(UUID itineraryId);
}
