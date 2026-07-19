# K6 로컬 부하 테스트

주요 API 엔드포인트에 대한 [k6](https://k6.io) 부하 테스트 스크립트. 실행 환경은 **로컬(localhost)** 기준이다.

> 관련 이슈: #14

---

## 1. K6 설치

| 환경 | 명령 |
|------|------|
| macOS | `brew install k6` |
| Windows | `choco install k6` 또는 `winget install k6 --source winget` |
| Linux (deb) | `sudo apt-get install k6` (사전 저장소 등록 필요) |
| Docker | `docker pull grafana/k6` |

설치 확인:
```bash
k6 version
```

---

## 2. 사전 조건

1. **백엔드 서버가 `dev` 프로파일로 로컬 실행 중**이어야 한다 (기본 `spring.profiles.active=dev`).
   ```bash
   ./gradlew bootRun
   ```
   - `dev` 프로파일에서만 `/dev/auth/token`(테스트용 JWT 발급)이 활성화된다.
   - PostgreSQL / Redis가 함께 떠 있어야 한다. (AI 서버는 불필요 — 아래 참고)

> **AI/LLM 부하는 이 스위트의 범위가 아니다.** 메시지 전송(`POST /chat-messages`)은 AI 서버로
> 스트리밍 응답을 생성해 토큰 비용이 크고, AI 성능은 AI 파트에서 별도 평가한다.
> 따라서 여기서는 채팅방·일정 등 **백엔드 자체의 CRUD/조회 처리량**만 측정하며,
> 포함된 모든 스크립트는 AI 서버 없이 토큰 비용 0으로 실행된다.
2. **인증 토큰** — 두 가지 방식 중 하나:
   - (기본) `CLERK_ID`(기본값 `load-test-user`)로 `dev-{clerkId}` 토큰을 만들어 쓴다.
     `DevSecurityConfig`의 dev 디코더가 **Clerk API 호출 없이** 즉시 인증하므로 로컬에서 바로 돌아간다.
     별도 Clerk 유저가 없어도 되고, `setup()`의 signup이 해당 clerkId를 `users`에 넣어 FK를 충족한다.
   - (대안) 실제 Clerk JWT로 검증 비용까지 재고 싶으면 `TOKEN` 환경변수로 직접 주입한다.

> 참고: 스크립트 `setup()`은 `POST /api/v1/users/signup`으로 유저 행을 먼저 보장한다
> (`chat_rooms.clerk_id` → `users` FK 때문). 별도 시드 작업은 필요 없다.

### 모니터링 스택 (선택)

k6 지표를 **Grafana 대시보드로 실시간 시각화**하고 싶을 때만 필요하다. **전체 기동 순서(DB → 모니터링
스택 → 백엔드 → k6)·대시보드 구성·트러블슈팅은 [`monitoring/README.md`](../../monitoring/README.md)가 정본**이다.
여기서는 부하 스크립트 관점의 요점만 정리한다.

- 두 축을 함께 본다: **(A)** 백엔드 `/actuator/prometheus` 스크레이프(CPU/Heap/GC·R2DBC 풀) +
  **(B)** k6가 remote-write로 미는 rps·p95·에러율·VU.
- **(A) 앱 설정은 이미 dev 프로파일에 있다** — `application-dev.properties`가 `prometheus` 엔드포인트를
  노출하고 `DevSecurityConfig`가 `/actuator/**`를 인증 없이 연다(prod엔 없음). 그래서 **`dev`로 띄우면 추가 설정 불필요**.
- **(B) k6 전송은 `loadrun.sh` 전용이 아니다** — 수동 `k6 run`에도 `--out experimental-prometheus-rw`만
  붙이면 된다(`loadrun.sh`는 이 플래그를 자동으로 넣어줄 뿐):
  ```bash
  K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
  K6_PROMETHEUS_RW_TREND_STATS="p(90),p(95),p(99)" \
    k6 run --out experimental-prometheus-rw \
    -e ENDPOINT=get-itinerary -e MODE=vus -e VUS=400 -e DURATION=60s scripts/load/stress-endpoint.js
  ```
- 그래프가 채워지려면 스크레이프(5s) 기준 **`DURATION`을 60s 이상**으로 잡는다.

> 대시보드 없이 터미널 요약만으로 충분하면 이 절을 건너뛰고 §3의 수동 `k6 run`을 그대로 쓴다.

---

## 3. 실행 방법

```bash
# 0) 환경 확인용 스모크 (VU 1, 각 엔드포인트 1회)
k6 run scripts/load/smoke.js

# (수동 정리) 중단된 실행 등으로 데이터가 남았을 때만 사용
k6 run scripts/load/cleanup.js
```

### 엔드포인트별 부하/스트레스 (한계점 탐색)

`stress-endpoint.js`로 `-e ENDPOINT=<값>`으로 대상을 고르고, **두 축** 중 하나로 부하를 올린다:

- **`MODE=rps`(기본)** — `-e RATE=`를 올려가며 **"초당 N건을 버티나?"**(처리량) 측정
- **`MODE=vus`** — `-e VUS=`를 올려가며 **"동시 접속 N명을 버티나?"**(동시성) 측정. 지연이 VU에 비례해 증가.

```bash
# [처리량 축] 읽기 RPS 스윕 — 목록은 8000까지 여유, 상세(get-itinerary)는 ~5000에서 포화
for EP in get-chatrooms get-itineraries get-itinerary; do
  for R in 2000 5000 8000; do k6 run -e ENDPOINT=$EP -e RATE=$R -e DURATION=12s scripts/load/stress-endpoint.js; done
done
# [처리량 축] 쓰기 — 생성 / 수정(수정은 리소스 풀 필요)
for R in 500 1500 3000; do k6 run -e ENDPOINT=post-chatroom  -e RATE=$R -e DURATION=12s scripts/load/stress-endpoint.js; done
for R in 500 1500 3000; do k6 run -e ENDPOINT=patch-dayplans -e RATE=$R -e DURATION=12s -e POOL=400 scripts/load/stress-endpoint.js; done

# [동시성 축] VU 스윕 — 동시 접속 수를 올려가며 지연/에러 관찰
for V in 100 200 400 800; do k6 run -e ENDPOINT=get-itinerary  -e MODE=vus -e VUS=$V -e DURATION=20s scripts/load/stress-endpoint.js; done
for V in 100 200 400 800; do k6 run -e ENDPOINT=patch-dayplans -e MODE=vus -e VUS=$V -e DURATION=20s scripts/load/stress-endpoint.js; done
```

> ⚠️ **로깅 주의**: dev 프로파일 기본 로깅은 요청 단위 `DEBUG/TRACE`라 고부하에서 초당 수십 MB 로그를
> 쏟아내 성능을 왜곡하고 서버를 다운시킬 수 있다(실측: VU100/30초에 로그 649MB, 서버 크래시).
> 스트레스 측정 시 실행 시점에 로깅을 낮춰 기동할 것:
> ```bash
> JAVA_TOOL_OPTIONS="-Dlogging.level.root=WARN -Dlogging.level.reactor.netty.http.server=WARN \
>  -Dlogging.level.org.springframework.security=WARN \
>  -Dlogging.level.org.springframework.security.oauth2.server.resource=WARN" ./gradlew bootRun
> ```
> 실측: 로깅 ON 상태로 부하 중 서버가 크래시했고, WARN으로 낮춘 뒤 정상 측정됐다.
> 엔드포인트별 실측 결과는 `results/20260714-233224-endpoints.md` 참고.

> **자동 정리**: 모든 부하 스크립트는 `teardown()`에서 테스트가 생성한 채팅방(+cascade로 일정·메시지)을
> 전부 삭제한다. 정상 종료 시 별도 정리가 필요 없다. k6가 중간에 강제 종료(Ctrl+C 등)되면
> teardown이 실행되지 않을 수 있으므로, 그때만 `cleanup.js`로 수동 정리한다.

### 자동 스윕 러너 (권장) — `loadrun.sh`

위 수동 루프(§3의 for 문)를 **핵심 5종 × 두 축(RPS/VU)** 전부 한 번에 돌리는 러너다.
[모니터링 스택](#모니터링-스택-선택)(→ 정본 [`monitoring/README.md`](../../monitoring/README.md))을 켜고
지표를 Prometheus로 전송하며(수동 `k6 run`으로도 동일 전송 가능), `20260715-monitored.md` 보고서가 이 스크립트로 생성됐다.

```bash
# 백그라운드 분리(detach) 실행 — 터미널/VSCode가 죽어도 스윕은 완주. PID·로그 경로 출력 후 즉시 반환.
bash scripts/load/loadrun.sh
# 진행 상황 확인
tail -f scripts/load/results/auto/run.log
# 이전 결과·마커 전부 지우고 처음부터
FRESH=1 bash scripts/load/loadrun.sh
# 중단 (앞의 '-'는 프로세스그룹 전체 = 러너+k6 동반 종료)
kill -TERM -<PID>
```

- **전제**: DB·모니터링·백엔드를 **직접 띄운 상태**여야 한다(러너는 서버/도커를 올리지 않고 헬스만 확인).
- **이어하기**: 레벨별 `results/auto/{label}.done` 마커로, 중간에 끊겨도 완료 레벨은 건너뛴다.
- **안전장치**: RPS 모드 `MAXVUS=1200` 캡(포화 시 VU 폭증=메모리 폭탄 차단), 헬스 실패 시 남은 스윕 중단.
- 결과 JSON은 `results/auto/{label}.json`(`--summary-export`)에 레벨별로 쌓인다.

### 결과 요약 추출 — `summarize.sh`

`loadrun.sh`가 쌓은 `results/auto/*.json`에서 보고서 표에 붙일 값(달성 RPS·p95·avg·에러율·drop/vus_max)을 뽑는다.
`name` 태그 하위지표만 사용해 teardown 정리 요청을 제외한 **순수 지표**를 계산한다(달성 RPS = count/부하창).

```bash
bash scripts/load/summarize.sh          # results/auto/ 전체
bash scripts/load/summarize.sh 43-iti   # 라벨 prefix 필터 (예: get-itinerary만)
```

### 환경변수 오버라이드

| 변수 | 기본값 | 사용 스크립트 | 설명 |
|------|--------|--------------|------|
| `BASE_URL` | `http://localhost:8080` | 전체 | 대상 서버 |
| `CLERK_ID` | `load-test-user` | 전체 | dev 토큰 발급용 clerkId |
| `TOKEN` | (없음) | 전체 | 미리 발급한 JWT 직접 주입 |
| `ENDPOINT` | `get-itinerary` | stress-endpoint | 측정 대상 엔드포인트 (아래 값 중 하나) |
| `MODE` | `rps` | stress-endpoint | 부하 축: `rps`(처리량, arrival-rate) / `vus`(동시성, constant-vus) |
| `RATE` | `500` | stress-endpoint (MODE=rps) | 초당 요청 수(RPS) — 올려가며 한계 탐색 |
| `VUS` | `100` | stress-endpoint (MODE=vus) | 동시 접속 가상 사용자 수 — 올려가며 한계 탐색 |
| `DURATION` | `rps=15s / vus=20s` | stress-endpoint | 부하 유지 시간 (`12s`, `30s` 등) |
| `POOL` | `100` | stress-endpoint | 수정(PATCH) 엔드포인트의 리소스 풀 크기. VU↔리소스 1:1 보장용(동시 작업자 수 이상으로 자동 확장) |

**`ENDPOINT` 가능한 값** (`stress-endpoint.js`):

| 값 | 실제 요청 | 유형 |
|----|----------|------|
| `get-chatrooms` | `GET /api/v1/chat-rooms` | 읽기 (핵심) |
| `get-itineraries` | `GET /api/v1/itineraries` | 읽기 (핵심) |
| `get-itinerary` | `GET /api/v1/itineraries/{id}` | 읽기 (핵심) |
| `post-chatroom` | `POST /api/v1/chat-rooms` | 쓰기 (핵심) |
| `patch-dayplans` | `PATCH /api/v1/itineraries/{id}/day-plans` | 쓰기 (핵심) |
| `get-chatroom` / `get-logs` / `get-reservations` | 각 GET 상세/로그/예약 | 읽기 (보조) |
| `patch-name` / `patch-itinerary` / `patch-status` / `patch-item-status` | 각 수정 | 쓰기 (보조) |
| `post-reservation` / `patch-reservation` | 예약 생성/수정 | 쓰기 (보조) |

예 (`ENDPOINT`는 필수):
```bash
# 처리량(RPS) 축 — 기본
k6 run -e ENDPOINT=get-itinerary -e RATE=5000 -e DURATION=12s scripts/load/stress-endpoint.js
# 동시성(VU) 축 — MODE=vus
k6 run -e ENDPOINT=get-itinerary -e MODE=vus -e VUS=400 -e DURATION=20s scripts/load/stress-endpoint.js
k6 run -e TOKEN="eyJhbGc..." -e ENDPOINT=get-chatrooms -e RATE=2000 scripts/load/stress-endpoint.js
k6 run -e ENDPOINT=post-chatroom -e RATE=3000 -e DURATION=12s scripts/load/stress-endpoint.js
k6 run -e ENDPOINT=patch-dayplans -e RATE=1500 -e DURATION=12s -e POOL=400 scripts/load/stress-endpoint.js
```

### 결과 요약 저장
```bash
k6 run --summary-export scripts/load/results/latest-summary.json -e ENDPOINT=get-itinerary -e RATE=5000 scripts/load/stress-endpoint.js
```

---

## 4. 부하/스트레스 핵심 대상 엔드포인트 (엄선)

**고트래픽·핵심 액션·고비용** 기준으로 아래 5개를 부하/스트레스의 공식 대상으로 삼는다.

| 도메인 | 메서드/경로 | 유형 | 왜 꼭 테스트하나 |
|--------|-------------|------|------------------|
| 채팅방 | `POST /api/v1/chat-rooms` | 쓰기 | 여행 계획 생성 = 핵심 생성 액션. 트랜잭션 3 INSERT라 **쓰기 병목 대표**. (과제 명시) |
| 채팅방 | `GET /api/v1/chat-rooms` | 읽기 | 홈 화면 채팅방 목록 = 최다 진입 조회 |
| 일정 | `GET /api/v1/itineraries` | 읽기 | 일정 목록 조회 (과제 명시: 일정 조회) |
| 일정 | `GET /api/v1/itineraries/{id}` | 읽기 | 일정 상세 = 앱 핵심 산출물, 최다 조회 화면 (과제 명시) |
| 일정 | `PATCH /api/v1/itineraries/{id}/day-plans` | 쓰기 | 일정 편집 = **가장 무거운 쓰기**(JSONB 재작성 + 스냅샷). 쓰기 상한 규명 |

**제외(정당 사유)**
- `POST /api/v1/chat-messages`(SSE+AI) — AI 파트에서 별도 평가
- 그 외 PATCH/DELETE·예약 CRUD — 부차적(저트래픽)이라 핵심 대상에서 제외
- `POST /users/signup`·`DELETE /users/signout` — 멱등 no-op / Clerk 계정 파괴(위험)

> 참고: `smoke.js`는 sanity 확인용으로 위 외 몇 개(GET 상세/로그/예약)까지 1회씩 훑는다.
> 부하·스트레스 측정은 위 5개에 집중한다.

> **추후 다른 엔드포인트 테스트가 필요하면** 3절의 `ENDPOINT` 값 표에서 대상을 골라 추가하여 실행합니다.
> (`stress-endpoint.js`가 보조 엔드포인트까지 지원하므로 별도 스크립트 작성 없이 `-e ENDPOINT=<값>`만 바꾸면 됩니다.)

### 주의: 수정(PATCH) 부하 시 값을 매번 바꿀 것
`PATCH /itineraries/*`는 **직전과 동일한 값이면 400("No changes detected")** 을 반환한다.
따라서 수정 부하 테스트는 **반복마다 페이로드를 변화**시켜야 한다(스크립트가 카운터로 처리).
또한 특정 리소스를 여러 VU가 동시에 수정하면 행 경합으로 실패하므로, **VU마다 다른 리소스**를
쓰도록 리소스 풀을 구성한다(`stress-endpoint.js`가 처리).

---

## 5. 시나리오 파라미터

| 스크립트 | executor | 부하 | 유지 |
|----------|----------|------|------|
| smoke | shared-iterations | VU 1 / 각 엔드포인트 1회 | — |
| stress-endpoint (MODE=rps) | constant-arrival-rate | RATE 가변(초당 요청 수) | 레벨당 12~15s |
| stress-endpoint (MODE=vus) | constant-vus | VUS 가변(동시 접속 수) | 레벨당 20s |

---

## 6. 결과 지표 기준값 (Threshold)

`lib/config.js`에 정의. 위반 시 k6는 **종료 코드 99**로 실패 → CI 게이트로 사용 가능.

| 지표 | 기준 |
|------|------|
| `http_req_failed` (에러율) | < 1% |
| `http_req_duration` p95 | < 500ms |
| `http_req_duration` p99 | < 1000ms |
| `checks` 성공률 | > 99% |

---

## 7. 실행 결과 문서화

- **`results/RESULTS.md`** — 새 측정 시 복사해 채우는 **빈 템플릿**이다(기록 파일 아님).
  환경·시나리오·핵심 지표(p95/p99, 에러율, RPS) 표를 담고 있다.
- **실제 보고서**는 `results/{날짜}-{설명}.md`로 저장한다. 현재까지:
  - [`20260714-233224-endpoints.md`](results/20260714-233224-endpoints.md) — 순수 k6(모니터링 없음), 핵심 5종 한계 탐색
  - [`20260715-monitored.md`](results/20260715-monitored.md) — Prometheus+Grafana 연동, 동일 레벨 재현 + 오버헤드 비교
- `--summary-export` JSON은 `results/`에 함께 보관한다(`loadrun.sh` 사용 시 `results/auto/`에 레벨별 자동 저장,
  `summarize.sh`로 표 값 추출). 대시보드 스크린샷은 `results/img/`에 둔다.
