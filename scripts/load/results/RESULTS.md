# 부하 테스트 실행 결과

측정할 때마다 아래 표를 복사해 채운다. 요약 JSON은 같은 폴더에 함께 보관한다
(`k6 run --summary-export scripts/load/results/<날짜>-<시나리오>.json ...`).

---

## 실행 #1 — (예시 템플릿)

- **일시**: YYYY-MM-DD HH:MM (KST)
- **대상**: `http://localhost:8080` (dev 프로파일)
- **실행 머신**: (예: MacBook Pro M1, 16GB / Docker Compose)
- **스크립트**: `scripts/load/____.js`
- **명령**: `k6 run -e VUS=__ -e DURATION=__ scripts/load/____.js`

### 핵심 지표

| 지표 | 값 | 기준 | 통과 |
|------|----|------|------|
| 총 요청 수 | | — | — |
| 처리량 (RPS) | | — | — |
| 에러율 `http_req_failed` | | < 1% | ☐ |
| 지연 p95 | | < 500ms | ☐ |
| 지연 p99 | | < 1000ms | ☐ |
| checks 성공률 | | > 99% | ☐ |

### 엔드포인트별 p95 (선택)

| 엔드포인트 (`name` 태그) | 요청 수 | p95 | 에러율 |
|---------------------------|---------|-----|--------|
| `GET /chat-rooms` | | | |
| `POST /chat-rooms` | | | |
| `GET /itineraries` | | | |
| `GET /itineraries/{id}` | | | |

### 관찰/병목

-

### 조치/후속

-
