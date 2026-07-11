package com.mju.capstone_backend.domain.itinerary.dto;

import java.time.LocalDate;
import java.util.UUID;

public record ItinerarySummary(
        UUID id,
        String name,
        String status,
        String origin,
        String destinations,
        int totalDays,
        LocalDate startDate
) {}
