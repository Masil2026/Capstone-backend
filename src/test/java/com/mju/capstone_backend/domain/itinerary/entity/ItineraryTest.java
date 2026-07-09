package com.mju.capstone_backend.domain.itinerary.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Itinerary 엔티티 단위 테스트")
class ItineraryTest {

    @Test
    @DisplayName("당일치기(start==end) 생성 시 totalDays=1, dayPlans에 날짜 1개만 생성됨")
    void of_dayTrip_startEqualsEnd() {
        LocalDate date = LocalDate.of(2026, 5, 1);

        Itinerary itinerary = Itinerary.of(
                UUID.randomUUID(),
                "[{\"city\":\"도쿄\",\"start_date\":\"2026-05-01\",\"end_date\":\"2026-05-01\"}]",
                BigDecimal.valueOf(200000), 1, 0, "[]",
                date, date
        );

        assertThat(itinerary.getTotalDays()).isEqualTo(1);
        assertThat(itinerary.getDayPlans()).isEqualTo("{\"2026-05-01\":[]}");
    }

    @Test
    @DisplayName("다일차 여행 생성 시 totalDays와 dayPlans가 날짜 범위만큼 생성됨 (회귀)")
    void of_multiDay_buildsAllDates() {
        LocalDate start = LocalDate.of(2026, 5, 1);
        LocalDate end = LocalDate.of(2026, 5, 3);

        Itinerary itinerary = Itinerary.of(
                UUID.randomUUID(),
                "[{\"city\":\"도쿄\",\"start_date\":\"2026-05-01\",\"end_date\":\"2026-05-03\"}]",
                BigDecimal.valueOf(500000), 2, 0, "[]",
                start, end
        );

        assertThat(itinerary.getTotalDays()).isEqualTo(3);
        assertThat(itinerary.getDayPlans())
                .isEqualTo("{\"2026-05-01\":[],\"2026-05-02\":[],\"2026-05-03\":[]}");
    }
}
