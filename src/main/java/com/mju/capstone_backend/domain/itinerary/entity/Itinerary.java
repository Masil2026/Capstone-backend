package com.mju.capstone_backend.domain.itinerary.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Table("itineraries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Itinerary implements Persistable<UUID> {

    @Id
    private UUID id;

    private UUID roomId;

    /** JSONB 컬럼 — DB에서 String으로 읽힘, 서비스 레이어에서 직렬화/역직렬화 */
    private String destinations;

    private LocalDate startDate;
    private LocalDate endDate;
    private int totalDays;
    private BigDecimal budget;
    private int adultCount;
    private int childCount;

    /** JSONB 컬럼 — DB에서 String으로 읽힘 */
    private String childAges;

    private String status;

    /** JSONB 컬럼 — DB에서 String으로 읽힘 */
    private String dayPlans;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Transient
    private boolean newEntity = false;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public static Itinerary of(UUID roomId, String destinationsJson,
                               BigDecimal budget, int adultCount, int childCount, String childAgesJson,
                               LocalDate startDate, LocalDate endDate) {
        Itinerary it = new Itinerary();
        it.id = UUID.randomUUID();
        it.roomId = roomId;
        it.destinations = destinationsJson;
        it.startDate = startDate;
        it.endDate = endDate;
        it.totalDays = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        it.budget = budget;
        it.adultCount = adultCount;
        it.childCount = childCount;
        it.childAges = childAgesJson;
        it.status = "draft";
        it.dayPlans = buildInitialDayPlans(startDate, endDate);
        it.createdAt = OffsetDateTime.now();
        it.updatedAt = OffsetDateTime.now();
        it.newEntity = true;
        return it;
    }

    public void updateBasicInfo(String destinationsJson, BigDecimal budget,
                                Integer adultCount, Integer childCount, String childAgesJson,
                                String updatedDayPlans,
                                LocalDate effectiveStart, LocalDate effectiveEnd) {
        if (destinationsJson != null) {
            this.destinations = destinationsJson;
            this.startDate = effectiveStart;
            this.endDate = effectiveEnd;
            this.totalDays = (int) ChronoUnit.DAYS.between(effectiveStart, effectiveEnd) + 1;
        }
        if (budget != null) this.budget = budget;
        if (adultCount != null) this.adultCount = adultCount;
        if (childCount != null) {
            this.childCount = childCount;
            this.childAges = childAgesJson;
        }
        if (updatedDayPlans != null) this.dayPlans = updatedDayPlans;
        this.updatedAt = OffsetDateTime.now();
    }

    public void updateDayPlans(String dayPlans) {
        this.dayPlans = dayPlans;
        this.updatedAt = OffsetDateTime.now();
    }

    public void updateStatus(String status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    private static String buildInitialDayPlans(LocalDate startDate, LocalDate endDate) {
        StringBuilder json = new StringBuilder("{");
        LocalDate current = startDate;
        boolean first = true;
        while (!current.isAfter(endDate)) {
            if (!first) json.append(",");
            json.append("\"").append(current).append("\":[]");
            current = current.plusDays(1);
            first = false;
        }
        json.append("}");
        return json.toString();
    }
}
