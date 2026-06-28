# Capstone Backend — Claude 지침

## 참고 문서
- @docs/conventions.md — 코딩 스타일 & 테스트 패턴 (상세)
- @docs/db-schema.md — DB 스키마 & Flyway 이력
- @docs/api/ — API 명세서 (엔드포인트별, 파일명: `{HTTP메서드}_v1_{도메인}_{액션}.md`)
  - 예) `docs/api/v1/users/POST_v1_users_signup.md`
  - 특정 엔드포인트 작업 시 해당 파일을 먼저 읽는다

---

## 기술 스택 요약
- **Java 21 / Spring Boot 3.5.x WebFlux**
- **DB:** PostgreSQL(R2DBC, 비동기) + Redis(Reactive, 비동기)
- **인증:** Clerk JWT (OAuth2 Resource Server)
- **마이그레이션:** Flyway / **문서:** SpringDoc OpenAPI(WebFlux)

---

## 핵심 코딩 규칙

### 패키지 구조
새 도메인은 반드시 아래 구조를 따른다:
```
domain/{name}/
├── controller/  {Name}Controller.java
├── service/     {Name}Service.java (interface) + {Name}ServiceImpl.java
├── repository/  {Name}Repository.java  (ReactiveCrudRepository)
└── entity/      {Name}.java
```

### 의존성 주입
- 필드 주입(`@Autowired`) 금지 — 반드시 생성자 주입
- Lombok `@RequiredArgsConstructor` 사용

### R2DBC 네이티브 Reactive 패턴
R2DBC 리포지토리는 네이티브 리액티브 — 별도 스케줄러 격리 불필요:
```
return repository.save(entity)   // Mono<T> 직접 반환
return repository.findById(id)   // Mono<T> 직접 반환
```
- `.then(publisher)` / `.thenMany(publisher)`는 체인 조립 시 즉시 호출됨 (eager)
- 구독 시점에 실행되어야 하면 `.flatMap(ignored -> publisher)` 또는 `Mono.defer(...)` 사용

### Entity
- `@Table("table_name")` 사용 (`@Entity` 없음 — R2DBC)
- `@NoArgsConstructor(access = AccessLevel.PROTECTED)` 필수
- 생성은 정적 팩터리 메서드 `{Name}.of(...)` 사용
- 새 엔티티 감지 필요 시 `Persistable<T>` 구현 + `@Transient boolean newEntity` 필드 추가

---

## 에러 처리 규칙

모든 에러는 `{"status": N, "error": "...", "message": "..."}` 형식으로 반환한다.

| 상황 | 핸들러 |
|------|--------|
| JWT 없음/만료 (401) | `SecurityErrorHandler` |
| 권한 없음 (403) | `SecurityErrorHandler` |
| 비즈니스 예외 (404, 500 등) | `GlobalExceptionHandler` |

새 예외 추가 시 `global/exception/` 패키지만 수정한다. SecurityConfig는 건드리지 않는다.

---

## 핵심 테스트 규칙

| 레이어 | 애노테이션 | 외부 서비스 |
|--------|-----------|------------|
| Service | `@ExtendWith(MockitoExtension.class)` | `@Mock` Repository |
| Controller | `@SpringBootTest` + `@AutoConfigureWebTestClient` + `@ActiveProfiles("test")` + `@TestPropertySource("file:.env")` | H2 + `.env` 자격증명 + `mockJwt()` |
| Config/인프라 | `@SpringBootTest` + `@TestPropertySource(locations = "file:.env")` | 실제 Supabase/Redis |

- 리액티브 검증은 `StepVerifier` 사용
- JWT는 `SecurityMockServerConfigurers.mockJwt()` 사용

---
