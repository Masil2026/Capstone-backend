#!/bin/bash
# results/auto/*.json (k6 --summary-export) → monitored.md 4절 표에 붙일 값 추출.
#   - name 태그 하위지표만 사용(teardown 정리요청 제외한 순수 지표)
#   - RPS 모드: 달성RPS / p95(ms) / 에러율% / dropped
#   - VU  모드: 달성RPS / p95(ms) / avg(ms) / 에러율%  (+ vus_max)
# 사용법: bash scripts/load/summarize.sh            # auto/ 전체
#         bash scripts/load/summarize.sh 43-iti     # 라벨 prefix 필터
set -u
DIR="$(cd "$(dirname "$0")" && pwd)/results/auto"
FILTER="${1:-}"

# 부하창(load window) 초. ⚠️ k6의 http_reqs.rate 는 count/전체실행시간(부하+정리 teardown)이라,
# 삭제가 무거운 POST/PATCH는 rps가 깎여 나온다. 정확한 처리량은 count/부하창 으로 계산한다.
#   post-rps=15s, post-vus=10s, 그 외(reads·patch)=30s  (loadrun.sh의 DURATION과 일치)
window_of() {
  case "$1" in
    *post-rps*) echo 15 ;;
    *post-vus*) echo 10 ;;
    *)          echo 30 ;;
  esac
}

printf "%-22s %10s %9s %10s %10s %8s %11s\n" "label" "rps(win)" "count" "p95(ms)" "avg(ms)" "err%" "drop/vusmax"
printf -- "%.0s-" {1..84}; echo

for f in "$DIR"/*.json; do
  [[ -e "$f" ]] || { echo "(json 없음)"; break; }
  label="$(basename "$f" .json)"
  [[ -n "$FILTER" && "$label" != "$FILTER"* ]] && continue
  win="$(window_of "$label")"
  jq -r --arg label "$label" --argjson win "$win" '
    .metrics as $m
    | ($m | to_entries) as $e
    | (($e | map(select(.key|startswith("http_reqs{name:")))       | .[0].value.count)       // $m.http_reqs.count)           as $cnt
    | (($e | map(select(.key|startswith("http_req_duration{name:")))| .[0].value."p(95)")     // $m.http_req_duration."p(95)")  as $p95
    | (($e | map(select(.key|startswith("http_req_duration{name:")))| .[0].value.avg)         // $m.http_req_duration.avg)      as $avg
    | (($e | map(select(.key|startswith("http_req_failed{name:")))  | .[0].value.value)       // $m.http_req_failed.value)      as $err
    | (($m.dropped_iterations.count // 0)) as $drop
    | (($m.vus.max // 0)) as $vmax
    | [ $label,
        (($cnt/$win)|floor|tostring),
        ($cnt|tostring),
        ($p95*100|round/100|tostring),
        ($avg*100|round/100|tostring),
        (($err*10000|round/100)|tostring),
        (if $drop>0 then "drop " + ($drop|tostring) else "vus " + ($vmax|tostring) end)
      ] | @tsv
  ' "$f" | awk -F'\t' '{printf "%-22s %10s %9s %10s %10s %7s%% %11s\n",$1,$2,$3,$4,$5,$6,$7}'
done
