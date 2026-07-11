package com.mju.capstone_backend.domain.itinerary.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record GetItineraryLogsResponse(
        UUID itineraryId,
        List<LogItem> logs
) {
    public record LogItem(
            UUID logId,
            OriginItem origin,
            List<DestinationItem> destinations,
            BigDecimal budget,
            Integer adultCount,
            Integer childCount,
            List<Integer> childAges,
            Integer totalDays,
            LocalDate startDate,
            LocalDate endDate,
            Map<String, Object> dayPlans,
            OffsetDateTime createdAt
    ) {
    }
}
