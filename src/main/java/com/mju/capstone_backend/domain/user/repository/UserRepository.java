package com.mju.capstone_backend.domain.user.repository;

import com.mju.capstone_backend.domain.user.entity.User;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface UserRepository extends ReactiveCrudRepository<User, String> {
}
