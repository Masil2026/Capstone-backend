package com.mju.capstone_backend.domain.itinerary.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(example = """
        {
          "origin": {"city": "부산"},
          "destinations": [
            {"city": "도쿄", "start_date": "2026-05-01", "end_date": "2026-05-04"},
            {"city": "오사카", "start_date": "2026-05-04", "end_date": "2026-05-06"}
          ],
          "budget": 500000,
          "adultCount": 2,
          "childCount": 1,
          "childAges": [7]
        }
        """)
public record PatchItineraryRequest(
        OriginItem origin,
        List<DestinationItem> destinations,
        BigDecimal budget,
        Integer adultCount,
        Integer childCount,
        List<Integer> childAges
) {
}
