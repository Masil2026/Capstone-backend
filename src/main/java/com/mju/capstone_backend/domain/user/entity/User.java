package com.mju.capstone_backend.domain.user.entity;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;

@Table("users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User implements Persistable<String> {

    @Id
    @Column("clerk_id")
    private String id;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Transient
    private boolean newEntity = false;

    @Override
    public boolean isNew() {
        return newEntity;
    }

    public static User of(String clerkId) {
        User user = new User();
        user.id = clerkId;
        user.createdAt = OffsetDateTime.now();
        user.newEntity = true;
        return user;
    }
}
