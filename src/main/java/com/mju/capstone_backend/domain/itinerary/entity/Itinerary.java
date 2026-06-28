package com.mju.capstone_backend.domain.itinerary.entity;

import io.r2dbc.postgresql.codec.Json;
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

    /** JSONB 컬럼 — R2DBC가 jsonb로 바인딩하도록 Json 타입으로 저장, getter는 String 반환 */
    @Getter(AccessLevel.NONE)
    private Json destinations;

    private LocalDate startDate;
    private LocalDate endDate;
    private int totalDays;
    private BigDecimal budget;
    private int adultCount;
    private int childCount;

    @Getter(AccessLevel.NONE)
    private Json childAges;

    private String status;

    @Getter(AccessLevel.NONE)
    private Json dayPlans;

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Transient
    private boolean newEntity = false;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public String getDestinations() { return destinations != null ? destinations.asString() : null; }
    public String getChildAges()    { return childAges    != null ? childAges.asString()    : null; }
    public String getDayPlans()     { return dayPlans     != null ? dayPlans.asString()     : null; }

    public static Itinerary of(UUID roomId, String destinationsJson,
                               BigDecimal budget, int adultCount, int childCount, String childAgesJson,
                               LocalDate startDate, LocalDate endDate) {
        Itinerary it = new Itinerary();
        it.id = UUID.randomUUID();
        it.roomId = roomId;
        it.destinations = Json.of(destinationsJson);
        it.startDate = startDate;
        it.endDate = endDate;
        it.totalDays = (int) ChronoUnit.DAYS.between(startDate, endDate) + 1;
        it.budget = budget;
        it.adultCount = adultCount;
        it.childCount = childCount;
        it.childAges = Json.of(childAgesJson);
        it.status = "draft";
        it.dayPlans = Json.of(buildInitialDayPlans(startDate, endDate));
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
            this.destinations = Json.of(destinationsJson);
            this.startDate = effectiveStart;
            this.endDate = effectiveEnd;
            this.totalDays = (int) ChronoUnit.DAYS.between(effectiveStart, effectiveEnd) + 1;
        }
        if (budget != null) this.budget = budget;
        if (adultCount != null) this.adultCount = adultCount;
        if (childCount != null) {
            this.childCount = childCount;
            this.childAges = childAgesJson != null ? Json.of(childAgesJson) : null;
        }
        if (updatedDayPlans != null) this.dayPlans = Json.of(updatedDayPlans);
        this.updatedAt = OffsetDateTime.now();
    }

    public void updateDayPlans(String dayPlans) {
        this.dayPlans = Json.of(dayPlans);
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
