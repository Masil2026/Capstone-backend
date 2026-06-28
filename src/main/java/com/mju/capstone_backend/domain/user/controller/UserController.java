package com.mju.capstone_backend.domain.user.controller;

import com.mju.capstone_backend.domain.user.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@Tag(name = "User API", description = "사용자 관련 API")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "회원가입", description = "JWT의 sub 클레임을 clerk_id로 users 테이블에 저장. 이미 존재하면 무시.")
    @PostMapping("/signup")
    public Mono<ResponseEntity<Void>> signup(JwtAuthenticationToken authentication) {
        String clerkId = authentication.getToken().getSubject();
        return userService.signup(clerkId)
                .thenReturn(ResponseEntity.<Void>ok().build());
    }

    @Operation(summary = "회원탈퇴", description = "DB에서 유저 및 연관 데이터를 cascade 삭제한 뒤 Clerk에서도 계정을 삭제한다.")
    @DeleteMapping("/signout")
    public Mono<ResponseEntity<Void>> deleteAccount(JwtAuthenticationToken authentication) {
        String clerkId = authentication.getToken().getSubject();
        return userService.deleteAccount(clerkId)
                .thenReturn(ResponseEntity.<Void>noContent().<Void>build());
    }
}
