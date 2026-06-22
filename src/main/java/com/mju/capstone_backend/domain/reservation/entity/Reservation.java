package com.mju.capstone_backend.domain.reservation.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Table("reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Reservation implements Persistable<UUID> {

    @Id
    private UUID id;

    private UUID itineraryId;
    private String type;
    private String status;
    private String bookedBy;
    private String bookingUrl;
    private String externalRefId;

    @Getter(AccessLevel.NONE)
    private Json detail;

    private BigDecimal totalPrice;
    private String currency;
    private OffsetDateTime reservedAt;
    private OffsetDateTime cancelledAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Transient
    private boolean newEntity = false;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public String getDetail() { return detail != null ? detail.asString() : null; }

    public static Reservation of(UUID itineraryId, String type, String status, String bookedBy,
                                 String bookingUrl, String externalRefId, String detail,
                                 BigDecimal totalPrice, String currency, OffsetDateTime reservedAt) {
        Reservation r = new Reservation();
        r.id = UUID.randomUUID();
        r.itineraryId = itineraryId;
        r.type = type;
        r.status = status;
        r.bookedBy = bookedBy;
        r.bookingUrl = bookingUrl;
        r.externalRefId = externalRefId;
        r.detail = detail != null ? Json.of(detail) : null;
        r.totalPrice = totalPrice;
        r.currency = currency;
        r.reservedAt = reservedAt;
        r.createdAt = OffsetDateTime.now();
        r.updatedAt = OffsetDateTime.now();
        r.newEntity = true;
        return r;
    }

    public void update(String status, String detail, BigDecimal totalPrice,
                       String currency, OffsetDateTime reservedAt, OffsetDateTime cancelledAt) {
        if (status != null) this.status = status;
        if (detail != null) this.detail = Json.of(detail);
        if (totalPrice != null) this.totalPrice = totalPrice;
        if (currency != null) this.currency = currency;
        if (reservedAt != null) this.reservedAt = reservedAt;
        if (cancelledAt != null) this.cancelledAt = cancelledAt;
        this.updatedAt = OffsetDateTime.now();
    }
}
