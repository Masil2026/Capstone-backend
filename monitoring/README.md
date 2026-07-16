# 부하테스트 모니터링 (Prometheus + Grafana)

k6 부하테스트를 **Grafana 대시보드로 실시간 시각화**하기 위한 로컬 모니터링 스택.
두 가지를 함께 본다:

- **(A) 서버 내부 지표** — Spring Boot `/actuator/prometheus` (CPU/Heap/GC, HTTP 처리량·p95, R2DBC 풀)
- **(B) 부하 생성기 지표** — k6가 remote-write로 밀어넣는 rps·p95·에러율·VU

> 전제: 이 폴더는 `Capstone-backend` 안에 있고, DB(Postgres/Redis)는 `Capstone-DB/docker-compose.yml`로,
> 백엔드는 호스트에서 `./gradlew bootRun`으로 띄운다. 모니터링(Prometheus+Grafana)만 이 compose로 띄운다.

> 부하 스크립트(엔드포인트 종류·환경변수·자동 스윕 `loadrun.sh` 등)는 [`scripts/load/README.md`](../scripts/load/README.md)를 참고한다.
> 이 문서는 그중 **모니터링 스택 기동·대시보드** 부분의 정본이다.

---

## 전체 실행 순서

```bash
# 1) DB 기동 (Capstone-DB)
cd ../Capstone-DB && docker compose up -d && cd ../Capstone-backend

# 2) 모니터링 스택 기동 (Prometheus:9090, Grafana:3000)
docker compose -f monitoring/docker-compose.monitoring.yml up -d

# 3) 백엔드 기동 — 부하 중 로그 폭주 방지 위해 로깅 WARN (scripts/load/README.md 지침)
JAVA_TOOL_OPTIONS="-Dlogging.level.root=WARN \
 -Dlogging.level.reactor.netty.http.server=WARN \
 -Dlogging.level.org.springframework.security=WARN \
 -Dlogging.level.org.springframework.security.oauth2.server.resource=WARN" ./gradlew bootRun

# 4) actuator 지표 노출 확인
curl -s localhost:8080/actuator/prometheus | head
#    → Prometheus 타겟 상태: http://localhost:9090/targets  (spring-backend = UP 확인)

# 5) k6 부하 실행 — Prometheus remote-write 로 지표 전송
K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write \
K6_PROMETHEUS_RW_TREND_STATS="p(90),p(95),p(99)" \
k6 run --out experimental-prometheus-rw \
  -e ENDPOINT=get-itinerary -e MODE=vus -e VUS=400 -e DURATION=60s \
  scripts/load/stress-endpoint.js
```

### Grafana 접속
- URL: <http://localhost:3000>  (익명 Admin 허용, 로그인 필요 시 `admin` / `admin`)
- 대시보드 (좌측 → Dashboards → "Load Test" 폴더):
  - **Spring Backend — 서버 내부 지표 (A)**
  - **k6 Load — 부하 생성기 지표 (B)**

부하를 60초 이상 유지해야 그래프가 채워진다(스크레이프 5s 간격). 스윕 시 `DURATION`을 늘려 실행할 것.

---

## 왜 이렇게 두 축을 보나
- **A(서버)** 는 "왜 느려지는가"를 본다 — CPU 포화? Heap/GC? R2DBC 풀(max=20) 고갈(pending 증가)?
- **B(k6)** 는 "클라이언트가 실제로 겪은 처리량·지연·에러"를 본다.
- 두 그래프를 같은 시간축에서 겹쳐 보면 병목 위치가 드러난다.
  예) k6 p95는 치솟는데 서버 CPU는 여유 → 커넥션 풀/큐잉 병목 의심.

---

## 종료 / 정리
```bash
# 모니터링 스택만 종료 (수집 데이터 보존)
docker compose -f monitoring/docker-compose.monitoring.yml down

# 수집 데이터(볼륨)까지 삭제
docker compose -f monitoring/docker-compose.monitoring.yml down -v
```

---

## 트러블슈팅

| 증상 | 원인 / 조치 |
|------|-------------|
| Prometheus `spring-backend` 타겟 DOWN | 백엔드 미기동, 또는 방화벽. `curl localhost:8080/actuator/prometheus` 확인 |
| actuator가 401 | `DevSecurityConfig`에 `/actuator/**` permitAll 있는지 확인 (dev 프로파일에서만 공개) |
| Grafana A 대시보드가 빔 | `management.endpoints.web.exposure.include`에 `prometheus` 포함 확인 |
| R2DBC 풀 패널만 빔 | 메트릭명 차이 가능 — `curl localhost:8080/actuator/prometheus \| grep r2dbc`로 실제 이름 확인 후 패널 쿼리 수정 |
| k6 대시보드가 빔 | `--out experimental-prometheus-rw` + `K6_PROMETHEUS_RW_SERVER_URL` 지정했는지, Prometheus가 `--web.enable-remote-write-receiver`로 떴는지 확인 |
| p95/p99 패널만 빔 | k6 실행에 `K6_PROMETHEUS_RW_TREND_STATS="p(90),p(95),p(99)"` 빠짐 |
| macOS/Windows에서 타겟 DOWN | 컨테이너→호스트 접근은 `host.docker.internal` 사용(설정됨). Linux는 compose의 `host-gateway`로 처리 |
