#!/bin/bash
# monitored.md 부하 러너 — clean(07-14)과 동일 레벨을 모니터링 켠 상태로 재현.
# 두 축(RPS/VU) × 핵심 5종. 읽기/PATCH 30s, POST는 데이터 억제 위해 짧게. RPS maxVUs=1200 캡.
#
# 전제: DB / 모니터링(Prometheus·Grafana) / 백엔드는 "직접" 띄운 상태여야 함.
#       (이 스크립트는 서버·도커를 올리지 않는다. 헬스만 확인하고, 다운이면 안내 후 종료.)
#
# 지난 크래시 대비: 러너를 새 세션으로 분리(detach) → VSCode가 죽어도 스윕은 완주.
#                   레벨별 .done 마커로 이어하기 → 중간에 끊겨도 처음부터 다시 안 함.
#
# 사용법:
#   bash scripts/load/loadrun.sh          # 백그라운드 분리 실행(이어하기 기본). PID/로그 출력 후 즉시 반환.
#   FRESH=1 bash scripts/load/loadrun.sh  # 이전 결과/마커 전부 지우고 처음부터
#   tail -f scripts/load/results/auto/run.log   # 진행 상황
#   kill -TERM -<PID>                     # 그룹 전체 중단(앞의 '-' 주의)

set -u

PROJ=/Users/mac/Masil2026/Capstone-backend
OUT="$PROJ/scripts/load/results/auto"
LOG="$OUT/run.log"
GAP=90                                    # 실행 사이 텀(초) — 그래프 골짜기 확보 + [1m] rate baseline 복귀
FRESH=${FRESH:-0}                         # 1이면 이전 결과/마커 삭제 후 새 타임라인
HEALTH=http://localhost:8080/actuator/health

cd "$PROJ" || exit 1
mkdir -p "$OUT"

# ── 0단계: 새 세션으로 분리(detach). VSCode 터미널이 죽어도 살아남게. ──────────
#    setsid(리눅스) → python3(macOS) → nohup(최후) 순으로 새 세션 확보.
if [[ "${_LOADRUN_STAGE:-}" != "worker" ]]; then
  export _LOADRUN_STAGE=worker
  if command -v setsid >/dev/null 2>&1; then
    setsid bash "$0" "$@" >>"$LOG" 2>&1 &
  elif command -v python3 >/dev/null 2>&1; then
    python3 -c 'import os,sys; os.setsid(); os.execv("/bin/bash", ["/bin/bash"]+sys.argv[1:])' \
      "$0" "$@" >>"$LOG" 2>&1 &
  else
    nohup bash "$0" "$@" >>"$LOG" 2>&1 &
  fi
  pid=$!
  disown 2>/dev/null || true
  echo "[loadrun] 백그라운드 분리 실행 시작 (VSCode 죽어도 스윕은 계속 돎)"
  echo "[loadrun]   PID   : $pid"
  echo "[loadrun]   진행  : tail -f $LOG"
  echo "[loadrun]   중단  : kill -TERM -$pid   (앞의 '-' = 프로세스그룹 전체)"
  exit 0
fi

# ── 여기서부터 worker (터미널과 분리된 상태) ─────────────────────────────────
# worker의 stdout은 이미 run.log로 리다이렉트돼 있으므로 echo만 하면 로그에 남는다(tee 쓰면 이중 기록).
log(){ echo "$*"; }

# 이중 실행 방지 — 분리 실행이라 눈에 안 보여서 실수로 또 켜기 쉽다. 락으로 하나만 돌게.
LOCK="$OUT/.loadrun.lock"
if [[ -f "$LOCK" ]] && kill -0 "$(cat "$LOCK" 2>/dev/null)" 2>/dev/null; then
  log ">>> ABORT: 이미 실행 중(PID=$(cat "$LOCK")). 중복 실행 방지. 중단하려면 kill -TERM -<PID>."
  exit 3
fi
echo $$ > "$LOCK"
trap 'rm -f "$LOCK"; log ">>> [$(date +%H:%M:%S)] TERM/INT 수신 — 중단(이어하기 가능)"; exit 2' TERM INT
trap 'rm -f "$LOCK"' EXIT

health_ok(){ curl -s -o /dev/null --max-time 5 "$HEALTH"; }

run() {  # label, then k6 -e args
  local label="$1"; shift
  if [[ -f "$OUT/${label}.done" ]]; then
    log ">>> [$(date +%H:%M:%S)] SKIP  $label (이미 완료)"
    return 0
  fi
  # 실행 전 헬스 확인(짧은 재시도 — GC 일시정지 등 흡수). 끝내 죽었으면 남은 스윕 중단.
  local ok=""
  for _ in 1 2 3; do health_ok && { ok=1; break; }; sleep 2; done
  if [[ -z "$ok" ]]; then
    log ">>> [$(date +%H:%M:%S)] ABORT: 백엔드 헬스 실패 → 남은 실행 중단. 서버 확인 후 재실행하면 이어감."
    exit 2
  fi
  log ">>> [$(date +%H:%M:%S)] START $label :: $*"
  k6 run --quiet --out experimental-prometheus-rw \
    --summary-export="$OUT/${label}.json" "$@" scripts/load/stress-endpoint.js >>"$LOG" 2>&1
  local rc=$?
  log ">>> [$(date +%H:%M:%S)] END   $label (k6 exit=$rc)"   # 0=정상, 99=threshold 위반(데이터 정상)
  if [[ $rc -eq 0 || $rc -eq 99 ]]; then
    : > "$OUT/${label}.done"
  else
    log ">>> [$(date +%H:%M:%S)] $label 미완료(rc=$rc) — .done 미기록, 부분 summary 폐기. 재실행 시 다시 돎."
    rm -f "$OUT/${label}.json"
    exit 2
  fi
  log "--- gap ${GAP}s ---"
  sleep "$GAP"
}

export K6_PROMETHEUS_RW_SERVER_URL=http://localhost:9090/api/v1/write
export K6_PROMETHEUS_RW_TREND_STATS="p(90),p(95),p(99)"
export MAXVUS=1200                        # RPS 모드 VU 상한 캡(메모리 폭탄 방지). stress-endpoint.js가 -e로 읽음

# FRESH=1 이면 이전 결과/마커/로그 초기화(새 타임라인). 기본은 이어하기(append).
if [[ "$FRESH" == "1" ]]; then
  rm -f "$OUT"/*.json "$OUT"/*.done
  : > "$LOG"
  log "=================== LOAD RUN START (FRESH) $(date '+%F %T') ==================="
else
  log "=================== LOAD RUN RESUME $(date '+%F %T') ==================="
fi

# 사전 점검: 서버가 안 떠 있으면 여기서 바로 안내 후 종료(직접 기동 대상)
if ! health_ok; then
  log ">>> ABORT: 백엔드($HEALTH) 응답 없음. 먼저 서버/DB/모니터링을 띄운 뒤 다시 실행하세요."
  exit 2
fi
log ">>> 사전 점검 OK — 백엔드 정상. 스윕 시작 (MAXVUS=$MAXVUS, GAP=${GAP}s)"

# ── 4.1 GET /chat-rooms ──────────────────────────────────────────────────────
for R in 2000 5000 8000; do run "41-cr-rps${R}"  -e ENDPOINT=get-chatrooms -e RATE=$R -e DURATION=30s; done
for V in 100 400 800;   do run "41-cr-vus${V}"  -e ENDPOINT=get-chatrooms -e MODE=vus -e VUS=$V -e DURATION=30s; done

# ── 4.2 GET /itineraries ─────────────────────────────────────────────────────
for R in 2000 5000 8000; do run "42-its-rps${R}" -e ENDPOINT=get-itineraries -e RATE=$R -e DURATION=30s; done
for V in 100 400 800;   do run "42-its-vus${V}" -e ENDPOINT=get-itineraries -e MODE=vus -e VUS=$V -e DURATION=30s; done

# ── 4.3 GET /itineraries/{id} (읽기 병목) ────────────────────────────────────
for R in 2000 5000 8000; do run "43-iti-rps${R}" -e ENDPOINT=get-itinerary -e RATE=$R -e DURATION=30s; done
for V in 100 400 800;   do run "43-iti-vus${V}" -e ENDPOINT=get-itinerary -e MODE=vus -e VUS=$V -e DURATION=30s; done

# ── 4.4 POST /chat-rooms (데이터 생성 억제 위해 짧게) ────────────────────────
for R in 500 1500 3000; do run "44-post-rps${R}" -e ENDPOINT=post-chatroom -e RATE=$R -e DURATION=15s; done
for V in 100 400;       do run "44-post-vus${V}" -e ENDPOINT=post-chatroom -e MODE=vus -e VUS=$V -e DURATION=10s; done

# ── 4.5 PATCH /itineraries/{id}/day-plans (POOL=400 / VU 모드는 POOL=VU) ──────
for R in 500 1500 3000; do run "45-patch-rps${R}" -e ENDPOINT=patch-dayplans -e RATE=$R -e DURATION=30s -e POOL=400; done
for V in 100 400;       do run "45-patch-vus${V}" -e ENDPOINT=patch-dayplans -e MODE=vus -e VUS=$V -e DURATION=30s; done

log "=================== ALL DONE $(date '+%F %T') ==================="
