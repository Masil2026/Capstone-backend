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
import java.util.UUID;

@Table("itinerary_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ItineraryLog implements Persistable<UUID> {

    @Id
    private UUID id;

    private UUID itineraryId;

    @Getter(AccessLevel.NONE)
    private Json origin;

    @Getter(AccessLevel.NONE)
    private Json destinations;

    private BigDecimal budget;
    private Integer adultCount;
    private Integer childCount;

    @Getter(AccessLevel.NONE)
    private Json childAges;

    private Integer totalDays;
    private LocalDate startDate;
    private LocalDate endDate;

    @Getter(AccessLevel.NONE)
    private Json dayPlans;

    private OffsetDateTime createdAt;

    @Transient
    private boolean newEntity = false;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public String getOrigin()       { return origin        != null ? origin.asString()       : null; }
    public String getDestinations() { return destinations != null ? destinations.asString() : null; }
    public String getChildAges()    { return childAges    != null ? childAges.asString()    : null; }
    public String getDayPlans()     { return dayPlans     != null ? dayPlans.asString()     : null; }

    public static ItineraryLog of(Itinerary itinerary) {
        ItineraryLog log = new ItineraryLog();
        log.id = UUID.randomUUID();
        log.itineraryId = itinerary.getId();
        String origin = itinerary.getOrigin();
        log.origin = origin != null ? Json.of(origin) : null;
        String dest = itinerary.getDestinations();
        log.destinations = dest != null ? Json.of(dest) : null;
        log.budget = itinerary.getBudget();
        log.adultCount = itinerary.getAdultCount();
        log.childCount = itinerary.getChildCount();
        String ages = itinerary.getChildAges();
        log.childAges = ages != null ? Json.of(ages) : null;
        log.totalDays = itinerary.getTotalDays();
        log.startDate = itinerary.getStartDate();
        log.endDate = itinerary.getEndDate();
        String plans = itinerary.getDayPlans();
        log.dayPlans = plans != null ? Json.of(plans) : null;
        log.createdAt = OffsetDateTime.now();
        log.newEntity = true;
        return log;
    }
}
