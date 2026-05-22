package com.mju.capstone_backend.domain.user.service;

import com.mju.capstone_backend.domain.user.entity.User;
import com.mju.capstone_backend.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserServiceImpl 단위 테스트")
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ClerkApiClient clerkApiClient;

    @InjectMocks
    private UserServiceImpl userService;

    // 테스트용 즉시 실행 스케줄러 주입
    @BeforeEach
    void injectScheduler() throws Exception {
        var field = UserServiceImpl.class.getDeclaredField("dbScheduler");
        field.setAccessible(true);
        field.set(userService, Schedulers.immediate());
    }

    @Test
    @DisplayName("신규 사용자 - users 테이블에 INSERT")
    void signup_newUser_saveCalled() throws Exception {
        injectScheduler();
        String clerkId = "user_newClerkId";
        when(userRepository.existsById(clerkId)).thenReturn(false);
        when(userRepository.save(any(User.class))).thenReturn(User.of(clerkId));

        StepVerifier.create(userService.signup(clerkId))
                .verifyComplete();

        verify(userRepository).existsById(clerkId);
        verify(userRepository).save(any(User.class));
    }

    @Test
    @DisplayName("이미 존재하는 사용자 - INSERT 없이 무시")
    void signup_existingUser_saveNotCalled() throws Exception {
        injectScheduler();
        String clerkId = "user_existingClerkId";
        when(userRepository.existsById(clerkId)).thenReturn(true);

        StepVerifier.create(userService.signup(clerkId))
                .verifyComplete();

        verify(userRepository).existsById(clerkId);
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("회원탈퇴 - DB 삭제 후 Clerk 계정 삭제 호출")
    void deleteAccount_existingUser_deletesFromDbAndClerk() throws Exception {
        injectScheduler();
        String clerkId = "user_toDeleteClerkId";
        when(userRepository.existsById(clerkId)).thenReturn(true);
        doNothing().when(userRepository).deleteById(clerkId);
        when(clerkApiClient.deleteUser(clerkId)).thenReturn(Mono.empty());

        StepVerifier.create(userService.deleteAccount(clerkId))
                .verifyComplete();

        verify(userRepository).existsById(clerkId);
        verify(userRepository).deleteById(clerkId);
        verify(clerkApiClient).deleteUser(clerkId);
    }

    @Test
    @DisplayName("회원탈퇴 - DB에 없는 사용자도 Clerk 삭제는 호출")
    void deleteAccount_nonExistingUser_skipsDbDeleteButCallsClerk() throws Exception {
        injectScheduler();
        String clerkId = "user_notInDbClerkId";
        when(userRepository.existsById(clerkId)).thenReturn(false);
        when(clerkApiClient.deleteUser(clerkId)).thenReturn(Mono.empty());

        StepVerifier.create(userService.deleteAccount(clerkId))
                .verifyComplete();

        verify(userRepository).existsById(clerkId);
        verify(userRepository, never()).deleteById(any());
        verify(clerkApiClient).deleteUser(clerkId);
    }
}
