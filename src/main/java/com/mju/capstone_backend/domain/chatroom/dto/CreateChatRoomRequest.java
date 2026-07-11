package com.mju.capstone_backend.domain.chatroom.dto;

import com.mju.capstone_backend.domain.itinerary.dto.DestinationItem;
import com.mju.capstone_backend.domain.itinerary.dto.OriginItem;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.List;

@Schema(example = """
        {
          "origin": {"city": "서울"},
          "destinations": [
            {"city": "제주도", "start_date": "2026-05-01", "end_date": "2026-05-03"}
          ],
          "budget": 300000,
          "adultCount": 2,
          "childCount": 1,
          "childAges": [7]
        }
        """)
public record CreateChatRoomRequest(
        @NotNull OriginItem origin,
        @NotNull @NotEmpty List<DestinationItem> destinations,
        BigDecimal budget,
        @NotNull @Min(1) Integer adultCount,
        @NotNull @Min(0) Integer childCount,
        @NotNull List<Integer> childAges
) {
}
