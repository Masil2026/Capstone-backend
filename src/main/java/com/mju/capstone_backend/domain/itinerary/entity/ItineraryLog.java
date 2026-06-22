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
import java.util.UUID;

@Table("itinerary_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItineraryLog implements Persistable<UUID> {

    @Id
    private UUID id;

    private UUID itineraryId;

    /** JSONB 컬럼 — DB에서 String으로 읽힘 */
    private String destinations;

    private BigDecimal budget;
    private Integer adultCount;
    private Integer childCount;

    /** JSONB 컬럼 — DB에서 String으로 읽힘 */
    private String childAges;

    private Integer totalDays;
    private LocalDate startDate;
    private LocalDate endDate;

    /** JSONB 컬럼 — DB에서 String으로 읽힘 */
    private String dayPlans;

    private OffsetDateTime createdAt;

    @Transient
    private boolean newEntity = false;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public static ItineraryLog of(Itinerary itinerary) {
        ItineraryLog log = new ItineraryLog();
        log.id = UUID.randomUUID();
        log.itineraryId = itinerary.getId();
        log.destinations = itinerary.getDestinations();
        log.budget = itinerary.getBudget();
        log.adultCount = itinerary.getAdultCount();
        log.childCount = itinerary.getChildCount();
        log.childAges = itinerary.getChildAges();
        log.totalDays = itinerary.getTotalDays();
        log.startDate = itinerary.getStartDate();
        log.endDate = itinerary.getEndDate();
        log.dayPlans = itinerary.getDayPlans();
        log.createdAt = OffsetDateTime.now();
        log.newEntity = true;
        return log;
    }
}
