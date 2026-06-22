# Conventions — 코딩 스타일 & 테스트 패턴

## 1. 아키텍처

### 레이어 구조
```
domain/{name}/
├── controller/   HTTP 요청/응답, 위임만 담당
├── service/      비즈니스 로직 (interface + impl 분리)
├── repository/   데이터 접근 (ReactiveCrudRepository 확장)
└── entity/       R2DBC 도메인 객체
global/config/    공통 설정 빈 (DB, Redis, Security, WebClient 등)
```

### SOLID 적용
| 원칙 | 적용 방식 |
|------|-----------|
| SRP | 레이어별 단일 책임 (Controller는 HTTP만, Service는 비즈니스만) |
| OCP | Service는 interface 기반 — 구현체 교체 가능 |
| LSP | `{Name}ServiceImpl`은 `{Name}Service` 계약을 완전히 준수, 사전조건 강화 금지 |
| DIP | Controller → `{Name}Service` (interface) 의존, impl에 직접 의존 금지 |
| ISP | Service interface는 최소한의 메서드만 선언 |

---

## 2. 코딩 규칙

### 의존성 주입
```
// 올바른 방식
@RequiredArgsConstructor
public class UserController {
    private final UserService userService;  // interface 타입
}

// 금지
@Autowired
private UserService userService;
```

### Entity 작성
R2DBC 엔티티는 JPA `@Entity` 없이 `@Table`만 사용한다. 새 엔티티 감지를 위해 `Persistable<T>`를 구현한다:
```java
@Table("users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)  // 필수
public class User implements Persistable<String> {

    @Id
    private String id;

    @Transient
    private boolean newEntity;

    // 정적 팩터리 메서드로만 생성
    public static User of(String clerkId) {
        User user = new User();
        user.id = clerkId;
        user.newEntity = true;
        return user;
    }

    @Override public String getId() { return id; }
    @Override public boolean isNew() { return newEntity; }
}
```

### R2DBC 네이티브 Reactive 패턴
R2DBC 리포지토리는 네이티브 리액티브이므로 별도 스케줄러 격리가 불필요하다:
```java
// R2DBC — 바로 반환, 블로킹 없음
return userRepository.save(user);       // Mono<User>
return userRepository.findById(id);     // Mono<User>

// 복잡한 다단계 트랜잭션 — @Transactional 또는 TransactionalOperator
@Transactional
public Mono<Void> complexOperation() {
    return repository.save(entity1)
            .flatMap(saved -> repository.save(entity2))
            .then();
}
```

lazy 평가에 주의: `.then(publisher)` / `.thenMany(publisher)`는 체인 조립 시 즉시 `publisher`를 호출한다.
구독 시점에 실행되어야 할 경우 `.flatMap(ignored -> publisher)` 또는 `.then(Mono.defer(() -> publisher))`를 사용한다.

### Controller 응답 형식
```
@RestController
@RequestMapping("/api/{name}s")
@RequiredArgsConstructor
@Tag(name = "{Name} API")
public class {Name}Controller {

    private final {Name}Service {name}Service;

    @PostMapping("{path}")
    @Operation(summary = "...")
    @ResponseStatus(HttpStatus.OK)
    public Mono<Void> methodName(...) {
        return {name}Service.method(...);
    }
}
```

### Swagger 예시 데이터
Request Body가 있는 엔드포인트는 Swagger UI에서 바로 테스트할 수 있도록 **Request DTO 클래스**에 `@Schema(example = "...")`를 추가한다.

컨트롤러가 아닌 DTO에 붙이는 이유: Swagger의 `@RequestBody`와 Spring의 `@RequestBody`가 이름이 동일하여 컨트롤러에서 함께 사용하면 반드시 하나를 풀네임으로 써야 하는 문제가 생기기 때문이다.

```java
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(example = """
        {
          "field": "value"
        }
        """)
public record SomeRequest(
        @NotBlank String field
) {
}
```

- 예시 데이터는 `docs/api/` 하위 해당 엔드포인트 명세서의 **2.3 Body** 샘플과 동일하게 유지한다.
- Request Body가 없는 DTO(GET, DELETE 등)에는 추가하지 않는다.

### Flyway 마이그레이션 네이밍
```
V{버전}__{snake_case_설명}.sql
예) V1__create_users_table.sql
    V2__add_schedules_table.sql
```
- `V` 대문자, 언더스코어 2개(`__`), 버전은 순차 증가
- 한 번 적용된 파일은 절대 수정 금지

---

## 3. 에러 처리

### 공통 에러 응답 형식
모든 에러 응답은 `ErrorResponse` 형식으로 통일한다:
```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid or expired token."
}
```

### 에러 처리 레이어 구분
| 발생 위치 | 핸들러 | 담당 상태코드 |
|----------|--------|------------|
| Security 필터 레이어 | `SecurityErrorHandler` | 401, 403 |
| Controller 레이어 | `GlobalExceptionHandler` | 404, 500 등 |

`@RestControllerAdvice`는 Security 필터 예외를 잡지 못하므로 두 핸들러가 반드시 공존해야 한다.

### 패키지 구조
```
global/exception/
├── ErrorResponse.java          공통 에러 응답 record
├── SecurityErrorHandler.java   401(ServerAuthenticationEntryPoint)
│                               403(ServerAccessDeniedHandler)
└── GlobalExceptionHandler.java @RestControllerAdvice (그 외 예외)
```

### 새 예외 추가 방법
- **도메인 비즈니스 예외** (예: 리소스 없음) → `GlobalExceptionHandler`에 `@ExceptionHandler` 추가
- **보안 관련 예외** (예: 토큰 만료 세분화) → `SecurityErrorHandler`의 `commence()` 수정

---

## 4. 테스트 패턴

### Service 단위 테스트
R2DBC 리포지토리는 네이티브 리액티브이므로 `dbScheduler` 주입 없이 바로 Mockito로 Mock 가능하다:
```java
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    UserRepository userRepository;

    @InjectMocks
    UserServiceImpl userService;

    @Test
    void 신규유저_회원가입_저장호출() {
        given(userRepository.existsById("new_id")).willReturn(Mono.just(false));
        given(userRepository.save(any(User.class))).willReturn(Mono.just(User.of("new_id")));

        StepVerifier.create(userService.signup("new_id"))
                .verifyComplete();

        verify(userRepository).save(any(User.class));
    }
}
```

### Controller 슬라이스 테스트
```
@SpringBootTest
@AutoConfigureWebTestClient
@ActiveProfiles("test")
@TestPropertySource(locations = "file:.env")
class UserControllerTest {

    @Autowired
    WebTestClient webTestClient;

    @MockitoBean
    UserService userService;

    @Test
    void 유효한JWT_200반환() {
        given(userService.signup(any())).willReturn(Mono.empty());

        webTestClient
            .mutateWith(SecurityMockServerConfigurers.mockJwt())
            .post().uri("/api/users/signup")
            .exchange()
            .expectStatus().isOk();
    }

    @Test
    void JWT없음_401반환() {
        webTestClient
            .post().uri("/api/users/signup")
            .exchange()
            .expectStatus().isUnauthorized();
    }
}
```

### Mock 빈 선언
Spring Boot 3.4부터 `@MockBean`(`org.springframework.boot.test.mock.mockito`)은 deprecated되었다.
반드시 `@MockitoBean`(`org.springframework.test.context.bean.override.mockito`)을 사용한다:
```
// 올바른 방식 (Spring Boot 3.4+)
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@MockitoBean
UserService userService;

// 금지 (deprecated)
import org.springframework.boot.test.mock.mockito.MockBean;

@MockBean
UserService userService;
```

### 테스트 환경 설정
`application-test.properties` — DB만 H2로 전환, 나머지는 `.env`에서 공급:
- **DB** → H2 인메모리 (PostgreSQL 모드), Flyway 비활성화
- **Redis / Clerk JWT / AI URL** → `.env` 실제 자격증명 사용

모든 `@SpringBootTest` 기반 테스트는 `@TestPropertySource(locations = "file:.env")`를 함께 선언한다.
전체 테스트 스위트 실행 시 `.env`가 필수다.

### 테스트 원칙 요약
| 레이어 | 종류 | DB | 외부 서비스 |
|--------|------|----|------------|
| Service | Unit (Mockito) | Mock | Mock |
| Controller | Slice (SpringBootTest) | H2 | .env 자격증명 + mockJwt() |
| Config/인프라 | Integration | 실제 Supabase | 실제 Redis |

- 리액티브 반환값은 항상 `StepVerifier`로 검증
