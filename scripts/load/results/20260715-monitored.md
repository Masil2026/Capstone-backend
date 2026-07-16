# 부하 테스트 보고서 — 모니터링(Prometheus + Grafana) 연동 측정

> **측정 계획**: `20260714-233224-endpoints.md`(순수 k6, 모니터링 없음)와 **동일한 부하 레벨**을
> 이번엔 **Prometheus + Grafana를 켠 상태**로 다시 돌린다. 목적은 "한계 재탐색"이 아니라
> **① 대시보드 시각화(스크린샷)** + **② 모니터링 켠 상태 vs 순수 상태 처리량 비교**다.

---

## 1. 개요

- **왜 clean과 같은 레벨인가**: 천장(읽기 ~9–13k rps, 쓰기 3.9–4.6k rps)은 이미 `20260714-233224-endpoints.md`에서
  규명됐다. 이번엔 그 값을 다시 찾는 게 아니라, **같은 부하를 관측 스택 위에서 재현**해
  (a) Grafana 대시보드에 부하 구간을 남기고, (b) 모니터링 오버헤드가 처리량에 주는 영향을 clean과 비교한다.
- **측정 대상**: clean과 동일한 **핵심 5종**, **두 축**(처리량 RPS / 동시성 VU) 모두.
- **지속 시간만 조정**: clean은 12~15s였으나, Prometheus 스크레이프가 5초라 그 길이는 그래프에 점이 2~3개뿐이라
  스크린샷이 빈약하다. → **읽기/PATCH는 30s로 상향**(그래프 가독성 확보, 크래시났던 60s의 절반이라 안전).
  **POST는 데이터 대량 생성 억제**를 위해 짧게 유지(RPS 15s / VU 10s).
- **이전 크래시 교훈 반영**: 지난 실패는 부하 레벨이 아니라 **"60s × VU1000 × 모니터링"의 메모리 압력** 때문이었다
  (VSCode가 jetsam으로 죽으며 통합 터미널의 백엔드·k6를 함께 kill). 이번엔 **VU는 clean과 동일하게 ≤800**,
  RPS 모드 `maxVUs`를 **3000→1200으로 캡**(포화 시 VU 폭증=메모리 폭탄 차단), 러너는 **터미널과 분리(detach)**해 돌린다.

---

## 2. 측정 환경

| 항목 | 값 |
|------|----|
| 대상 | `http://localhost:8080` (dev 프로파일) |
| 실행 머신 | MacBook Pro (macOS arm64, 24GB) — 앱·DB·모니터링·부하생성기 **한 대 공유** |
| DB | Docker: Postgres `pgvector:pg16` `:5432`, Redis `7.2` `:6379` |
| 모니터링 | Docker: Prometheus `v2.54.1` `:9090`, Grafana `11.2.0` `:3000` (scrape 5s) |
| 앱 기동 | `./gradlew bootRun`, **로깅 WARN**(고부하 로그 폭주 방지) |
| 커넥션 풀 | R2DBC `initial-size=5 / max-size=20` |
| 부하 도구 | k6 v2.1.0, `--out experimental-prometheus-rw` (Prometheus로 지표 전송) |
| 인증 | dev 디코더 토큰(`dev-load-test-user`), AI 서버 미사용(토큰 비용 0) |
| **지속 시간** | 읽기/PATCH **30s**, POST **RPS 15s / VU 10s** |
| **RPS `maxVUs` 캡** | **1200** (`-e MAXVUS=1200`) — clean은 3000. 포화 시 VU 폭증 억제 |
| 러너 | `scripts/load/loadrun.sh` — 터미널과 분리(detach) 실행 + 레벨별 이어하기(resume) |

> ⚠️ 한 머신 공유이므로 **절대 용량이 아님** — 실제 배포(EC2 등)와 수치가 다르다. 경향·상대 비교용.

### 임계값(Threshold) — `scripts/load/lib/config.js`

| 지표 | 기준 |
|------|------|
| `http_req_failed` | < 1% |
| `http_req_duration` p95 | < 500ms |
| `http_req_duration` p99 | < 1000ms |
| `checks` 성공률 | > 99% |

### 두 축의 의미

- **동시성 축(MODE=vus)** = "동시 접속 N명 버티나?" — VU가 응답을 기다렸다 다음 요청(self-pacing). 서버 순수 처리 천장 + VU당 지연을 본다.
- **처리량 축(MODE=rps)** = "초당 N건 버티나?" — 도착률을 강제(open model). 포화 지점의 큐잉 붕괴·drop을 드러낸다.
- 두 축의 숫자는 **1:1로 비교하지 않는다**(재는 것이 다름).

---

## 3. 결론 요약 (TL;DR)

- **5종 전부 에러율 0%**, 임계값(p95<500·p99<1000·err<1%) **전 구간 통과**. 모니터링 켠 상태에서도 안정.
- **monitored 천장이 clean 대비 9~21% 낮다** = 관측 스택(actuator 히스토그램 + Prometheus 스크레이프 + k6 remote-write가
  앱과 CPU를 공유) 오버헤드. **성능 저하가 아니라 "관측을 켠 상태의 처리량"**(5절 비교표).
- **병목 정체 = R2DBC 커넥션 풀(20)**. 읽기 RPS 8000 / 쓰기 RPS 3000에서 **풀 pending이 ~1,150까지 폭증 → p95 200~750ms**로
  치솟는다(6절 스샷). WebFlux(논블로킹)라 **붕괴가 5xx 에러가 아니라 지연 증가·요청 drop으로** 나타난다.
- **읽기 최저 천장 = `GET /itineraries/{id}`(~7.9k rps)** — day_plans JSONB 파싱+index 재부여로 읽기 중 가장 무겁다.
  목록 읽기는 ~9.4–10k. **쓰기: POST ~3.8k / PATCH ~3.6k.**
- **두 축의 대비**: PATCH는 **RPS 3000까지 0 drop·p95 12ms**로 여유인데, POST는 **RPS 3000에서 붕괴**(달성 2,400·drop 8,989·p95 750ms).
  POST의 3-INSERT 트랜잭션이 커넥션을 더 오래 점유해 도착률 강제(open) 시 풀을 먼저 고갈시키기 때문. (VU closed 축에선 POST가 근소 우위 3.8k>3.6k.)

> **달성 RPS 산정**: 표의 `달성 RPS = count ÷ 부하창`(reads·PATCH 30s / POST-RPS 15s / POST-VU 10s).
> k6 summary의 `http_reqs.rate`는 **teardown(정리 삭제) 시간까지 포함한 전체 실행시간**으로 나눠 쓰기 엔드포인트에서 낮게 나오므로 쓰지 않는다.
> `count`는 `results/auto/<label>.json` 원본값 → 표의 rps는 `count ÷ 부하창`으로 **직접 검증 가능**.

---

## 4. 엔드포인트별 결과

> 값 출처: `results/auto/<label>.json`(k6 `--summary-export`), name 태그 하위지표(teardown 정리요청 제외).
> `bash scripts/load/summarize.sh` 로 일괄 추출. **달성 RPS = count ÷ 부하창**(위 3절 각주 참조).

### 4.1 `GET /chat-rooms` — 읽기 (홈 화면 채팅방 목록) ✅

가장 가벼운 읽기. RPS 8000도 p95 11ms·0 drop으로 여유. 동시성 천장 ~9.4k rps.

#### (a) 처리량 축 — RPS 2000 / 5000 / 8000 (각 30s)
| 목표 RATE | 달성 RPS | count | p95(ms) | 에러율 | dropped |
|----------:|--------:|------:|--------:|------:|--------:|
| 2,000 | 2,000 | 60,003 | 1.37 | 0% | 0 |
| 5,000 | 5,000 | 150,003 | 1.60 | 0% | 0 |
| 8,000 | 8,000 | 240,003 | 11.46 | 0% | 0 |

#### (b) 동시성 축 — VU 100 / 400 / 800 (각 30s)
| VU | 달성 RPS | count | p95(ms) | avg(ms) | 에러율 |
|----:|--------:|------:|--------:|--------:|------:|
| 100 | 9,435 | 283,076 | 12.86 | 10.56 | 0% |
| 400 | 8,370 | 251,101 | 57.54 | 47.71 | 0% |
| 800 | 8,618 | 258,543 | 109.63 | 92.66 | 0% |

---

### 4.2 `GET /itineraries` — 읽기 (일정 목록) ✅

RPS 8000에서 포화 시작(달성 7,741 · drop 7,762 · p95 195ms). 동시성 천장 ~10k rps.

#### (a) 처리량 축 — RPS 2000 / 5000 / 8000 (각 30s)
| 목표 RATE | 달성 RPS | count | p95(ms) | 에러율 | dropped |
|----------:|--------:|------:|--------:|------:|--------:|
| 2,000 | 2,000 | 60,001 | 1.27 | 0% | 0 |
| 5,000 | 5,000 | 150,000 | 1.18 | 0% | 0 |
| 8,000 | 7,741 | 232,238 | 194.71 | 0% | 7,762 |

#### (b) 동시성 축 — VU 100 / 400 / 800 (각 30s)
| VU | 달성 RPS | count | p95(ms) | avg(ms) | 에러율 |
|----:|--------:|------:|--------:|--------:|------:|
| 100 | 10,144 | 304,327 | 12.33 | 9.82 | 0% |
| 400 | 9,972 | 299,160 | 50.27 | 40.05 | 0% |
| 800 | 9,254 | 277,637 | 109.39 | 86.32 | 0% |

---

### 4.3 `GET /itineraries/{id}` — 읽기 병목 대표 (일정 상세) ✅

읽기 중 가장 무겁다(day_plans JSONB 파싱 + 아이템 시간순 index 재부여). **RPS 8000에서 크게 붕괴**(달성 6,948 · **drop 31,534** · p95 199ms).
동시성 천장 ~7.9k(읽기 중 최저). 원인은 R2DBC 풀 pending 폭증(6절 (A)-3 스샷).

#### (a) 처리량 축 — RPS 2000 / 5000 / 8000 (각 30s)
| 목표 RATE | 달성 RPS | count | p95(ms) | 에러율 | dropped |
|----------:|--------:|------:|--------:|------:|--------:|
| 2,000 | 2,000 | 60,001 | 1.45 | 0% | 0 |
| 5,000 | 5,000 | 150,001 | 7.63 | 0% | 0 |
| 8,000 | 6,948 | 208,468 | 198.68 | 0% | 31,534 |

#### (b) 동시성 축 — VU 100 / 400 / 800 (각 30s)
| VU | 달성 RPS | count | p95(ms) | avg(ms) | 에러율 |
|----:|--------:|------:|--------:|--------:|------:|
| 100 | 7,179 | 215,398 | 17.16 | 13.88 | 0% |
| 400 | 7,876 | 236,281 | 56.99 | 50.70 | 0% |
| 800 | 7,821 | 234,643 | 113.55 | 102.00 | 0% |

---

### 4.4 `POST /chat-rooms` — 쓰기 병목 대표 (여행 계획 생성, 3 INSERT) ✅

지속 시간 짧게(RPS 15s / VU 10s). **RPS 3000에서 포화**(달성 2,400 · drop 8,989 · p95 750ms) — 트랜잭션이 커넥션을 오래 잡아 풀 pending 폭증(6절 (B)-3 스샷). 동시성 천장 ~3.8k rps.

#### (a) 처리량 축 — RPS 500 / 1500 / 3000 (각 15s)
| 목표 RATE | 달성 RPS | count | p95(ms) | 에러율 | dropped |
|----------:|--------:|------:|--------:|------:|--------:|
| 500 | 500 | 7,501 | 3.45 | 0% | 0 |
| 1,500 | 1,500 | 22,500 | 2.75 | 0% | 0 |
| 3,000 | 2,400 | 36,012 | 750.11 | 0% | 8,989 |

#### (b) 동시성 축 — VU 100 / 400 (각 10s)
| VU | 달성 RPS | count | p95(ms) | avg(ms) | 에러율 |
|----:|--------:|------:|--------:|--------:|------:|
| 100 | 3,844 | 38,446 | 35.85 | 25.93 | 0% |
| 400 | 3,076 | 30,762 | 176.95 | 130.41 | 0% |

---

### 4.5 `PATCH /itineraries/{id}/day-plans` — 가장 무거운 쓰기 (JSONB 재작성 + 스냅샷) ✅

- `POOL=400`: VU마다 **다른 일정**을 수정해 행 경합 방지(같은 행 동시 수정 시 ~50% 400). RPS 모드에선 `maxVUs`도 풀 크기(=400)로 캡됨.
- 가장 무거운 쓰기지만 **RPS 3000까지 0 drop·p95 12ms**로 오히려 POST보다 여유(POST와 달리 도착률 강제에서도 안 무너짐). 동시성 천장 ~3.6k rps.

#### (a) 처리량 축 — RPS 500 / 1500 / 3000 (각 30s, POOL=400)
| 목표 RATE | 달성 RPS | count | p95(ms) | 에러율 | dropped |
|----------:|--------:|------:|--------:|------:|--------:|
| 500 | 500 | 15,001 | 2.33 | 0% | 0 |
| 1,500 | 1,500 | 45,000 | 2.54 | 0% | 0 |
| 3,000 | 3,000 | 90,000 | 12.26 | 0% | 0 |

#### (b) 동시성 축 — VU 100 / 400 (각 30s, POOL=VU)
| VU | 달성 RPS | count | p95(ms) | avg(ms) | 에러율 |
|----:|--------:|------:|--------:|--------:|------:|
| 100 | 3,409 | 102,274 | 36.53 | 29.25 | 0% |
| 400 | 3,565 | 106,960 | 147.00 | 112.16 | 0% |

---

## 5. 종합 비교표 — monitored vs clean ✅

동시성 축(VU) 천장 기준(VU 스윕 중 최대 달성 rps). clean 값은 `20260714-233224-endpoints.md`.

| 엔드포인트 | 유형 | clean 천장(rps) | **monitored 천장(rps)** | 오버헤드 | 에러율 |
|-----------|------|---------------:|------------------------:|--------:|-------:|
| `GET /chat-rooms` | 읽기 | ~12,000 | **~9,435** | −21% | 0% |
| `GET /itineraries` | 읽기 | ~12,800 | **~10,144** | −21% | 0% |
| `GET /itineraries/{id}` | 읽기 | ~9,000 | **~7,876** | −12% | 0% |
| `POST /chat-rooms` | 쓰기 | ~4,600 | **~3,844** | −16% | 0% |
| `PATCH day-plans` | 쓰기 | ~3,900 | **~3,565** | −9% | 0% |

- **관측 오버헤드로 전 구간 9~21% 하락**하나 순위·경향은 clean과 동일: 목록 읽기 > 상세 읽기 > 쓰기(POST≳PATCH).
- **RPS(open) 축의 포화 지점**: 상세 읽기 ~5–6k, 목록 읽기 ~7–8k, POST ~2.4k, PATCH ≥3k. 그 이상은 drop·지연 급증.
- **공통 병목은 R2DBC 커넥션 풀(20)** — 개선 시 풀 크기·쓰기 트랜잭션 경로가 1순위(6절 근거).

---

## 6. Grafana 대시보드 (스크린샷) ✅

각 엔드포인트를 **두 축(RPS/VU) × 두 대시보드**로 캡처. 파일: `results/img/<label>-<rps|vus>-<k6|springboot>.png`.
- **k6 Load(부하 생성기 관점)**: 처리량 rps / p95·p99 / 에러율 / VU / 네트워크
- **Spring Backend(서버 내부)**: URI별 처리량 / p95·p99 / 5xx / CPU / GC / JVM Heap / **R2DBC 풀**

### ⚠️ 그래프 읽는 법 — 정리(teardown) 트래픽 주의
각 실행은 **측정 대상 요청** 외에 `setup()`/`teardown()`의 부수 요청도 발생시킨다. 특히 **POST(4.4)·PATCH(4.5)**:
- **측정 부하** = `POST /chat-rooms`(또는 `PATCH …/day-plans`) — 15~30s **plateau 구간**.
- 그 **직후 뾰족한 스파이크** = `teardown` 정리: `GET /chat-rooms`(목록 조회) + `DELETE /chat-rooms/{id}`(생성분 **배치 병렬 삭제**, POST 1500rps면 ~22,500건이 몰림).

즉 **"넓은 plateau = 실제 테스트, 그 뒤 짧고 높은 스파이크 = 데이터 정리"**. 정리 트래픽은 name 태그가 달라
4절 표 측정값(`POST /chat-rooms` 하위지표)에는 **섞이지 않는다.** (5xx 패널의 "No data"는 5xx가 0건이라는 뜻 = 에러 0%.)

### ⚠️ `현재 VUs` 패널이 0으로 보이는 경우 (POST/PATCH)
`현재 VUs`는 **"마지막 값(last)" 단일 스탯**으로, k6가 Prometheus에 **마지막으로 push한 `k6_vus` 샘플**을 표시한다
(Prometheus가 그 값을 이어서 유지). k6는 지표를 5초마다 flush하므로, **런이 끝날 때의 마지막 flush가 어떤 VU 상태였는지**가 표시값을 결정한다 — 캡처 시점과는 무관하다.

- **읽기**: teardown(정리)이 즉시 끝나 k6가 **부하 피크(VU=캡 1200) 상태 그대로 프로세스를 종료** → 마지막 샘플 = 1200 → **"1.20 K"**.
- **POST/PATCH**: teardown이 방을 **대량 삭제(7~14초 추가 실행)**하는데, 그 사이 부하는 끝나 VU=0이라 k6가 **`vus=0`을 몇 번 더 push** → 마지막 샘플 = 0 → **"0"**.

즉 VU를 0개 썼다는 뜻이 **아니다**. 원인은 앞서 나온 **teardown 길이 비대칭**(읽기 즉시 vs 쓰기 대량삭제)이다.
> Prometheus 실측: `iti-rps8000`은 런 종료 후에도 `k6_vus=1200` 유지 / `post-rps3000`은 종료 직후 `1200→0`으로 하락.

실제 사용 VU는 **4절 표의 `vus_max`**(summarize.sh 출력) 또는 **패널 하단 스파크라인 봉우리**를 본다.

### 6.1 `GET /chat-rooms`
| 처리량 축 (RPS) | 동시성 축 (VU) |
|---|---|
| ![cr-rps-k6](img/41-cr-rps-k6.png) | ![cr-vus-k6](img/41-cr-vus-k6.png) |
| ![cr-rps-springboot](img/41-cr-rps-springboot.png) | ![cr-vus-springboot](img/41-cr-vus-springboot.png) |

### 6.2 `GET /itineraries`
| 처리량 축 (RPS) | 동시성 축 (VU) |
|---|---|
| ![its-rps-k6](img/42-its-rps-k6.png) | ![its-vus-k6](img/42-its-vus-k6.png) |
| ![its-rps-springboot](img/42-its-rps-springboot.png) | ![its-vus-springboot](img/42-its-vus-springboot.png) |

### 6.3 `GET /itineraries/{id}` (읽기 병목 — 풀 pending 폭증 확인)
| 처리량 축 (RPS) | 동시성 축 (VU) |
|---|---|
| ![iti-rps-k6](img/43-iti-rps-k6.png) | ![iti-vus-k6](img/43-iti-vus-k6.png) |
| ![iti-rps-springboot](img/43-iti-rps-springboot.png) | ![iti-vus-springboot](img/43-iti-vus-springboot.png) |

> RPS-springboot: RPS 8000 구간에서 **R2DBC 풀 pending ~1,150 폭증 + 전체 p95 ~210ms + 시스템 CPU 100%** → 병목이 커넥션 풀임을 직접 확인.

### 6.4 `POST /chat-rooms` (쓰기 병목)
| 처리량 축 (RPS) | 동시성 축 (VU) |
|---|---|
| ![post-rps-k6](img/44-post-rps-k6.png) | ![post-vus-k6](img/44-post-vus-k6.png) |
| ![post-rps-springboot](img/44-post-rps-springboot.png) | ![post-vus-springboot](img/44-post-vus-springboot.png) |

> RPS-springboot: RPS 3000 구간에서 **풀 pending ~1,150 + p95 ~750ms**. plateau 뒤 `DELETE /chat-rooms/{id}` 정리 스파이크가 함께 보임(위 "읽는 법").

### 6.5 `PATCH /itineraries/{id}/day-plans`
| 처리량 축 (RPS) | 동시성 축 (VU) |
|---|---|
| ![patch-rps-k6](img/45-patch-rps-k6.png) | ![patch-vus-k6](img/45-patch-vus-k6.png) |
| ![patch-rps-springboot](img/45-patch-rps-springboot.png) | ![patch-vus-springboot](img/45-patch-vus-springboot.png) |

---

## 7. 재현 방법

### 사전 준비 (직접 실행)
```bash
# 1) DB
cd ../Capstone-DB && docker compose up -d && cd ../Capstone-backend
# 2) 모니터링
docker compose -f monitoring/docker-compose.monitoring.yml up -d
# 3) 백엔드 (로깅 WARN) — VSCode 등에서 직접 기동
JAVA_TOOL_OPTIONS="-Dlogging.level.root=WARN -Dlogging.level.reactor.netty.http.server=WARN \
 -Dlogging.level.org.springframework.security=WARN" ./gradlew bootRun
```

### 부하 스윕 실행
서버·DB·모니터링이 모두 뜬 뒤, 러너를 **한 줄**로 실행한다(터미널과 분리되어 백그라운드로 돎):
```bash
bash scripts/load/loadrun.sh
# 진행 보기:  tail -f scripts/load/results/auto/run.log
# 처음부터 :  FRESH=1 bash scripts/load/loadrun.sh
```
러너는 매 실행 사이 90초 텀을 두고(그래프 골짜기 확보), 레벨별 `.done` 마커로 이어하기를 지원한다.
각 실행의 summary는 `results/auto/<label>.json`으로 저장된다.

### 4절 표 값 추출
`summarize.sh`가 `results/auto/*.json`에서 name 태그 순수 지표(teardown 제외)를 뽑아준다:
```bash
bash scripts/load/summarize.sh          # 전체
bash scripts/load/summarize.sh 43-iti    # 라벨 prefix 필터
# 출력: label / 달성rps(=count÷부하창) / count / p95(ms) / avg(ms) / 에러율% / (drop 또는 vus_max)
```
