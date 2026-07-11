package com.mju.capstone_backend.domain.itinerary.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GetItinerariesResponse(List<ItineraryItem> itineraries) {

    public record ItineraryItem(
            UUID itineraryId,
            String name,
            String status,
            OriginItem origin,
            List<DestinationItem> destinations,
            int totalDays,
            LocalDate startDate
    ) {}
}
