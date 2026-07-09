package com.mju.capstone_backend.domain.itinerary.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GetItineraryResponse(
        UUID itineraryId,
        String name,
        String status,
        OriginItem origin,
        List<DestinationItem> destinations,
        BigDecimal budget,
        int adultCount,
        int childCount,
        List<Integer> childAges,
        int totalDays,
        LocalDate startDate,
        LocalDate endDate,
        Map<String, List<Map<String, Object>>> dayPlans,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
