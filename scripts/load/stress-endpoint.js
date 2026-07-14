// 엔드포인트별 스트레스 — 드라이브 가능한 모든 API를 하나씩 개별 측정한다.
// 러너가 ENDPOINT env를 바꿔가며 반복 호출 → 경로별 처리량/지연 비교표 생성.
//
// 핵심: 수정(PATCH) 엔드포인트는 VU마다 "서로 다른 리소스(일정/예약)"를 만지도록 리소스 풀을 쓴다.
//   하나의 행을 여러 VU가 동시에 read-modify-write 하면 처리량이 아니라 "행 경합"을 재게 되고
//   실제로도 ~50% 400(트랜잭션+스냅샷 충돌)이 나기 때문. 실사용은 사용자마다 자기 리소스를 수정한다.
//
// 부하 모델 2종 (MODE로 선택):
//   MODE=rps  (기본) — constant-arrival-rate. "초당 N건(RATE)을 버티나?" 처리량 축. 생성 데이터량도 RATE로 제한.
//   MODE=vus         — constant-vus.          "동시 접속 N명(VUS)을 버티나?" 동시성 축. 지연이 VU에 비례.
//
// 실행:
//   k6 run -e ENDPOINT=get-itinerary  -e RATE=5000 -e DURATION=15s scripts/load/stress-endpoint.js         (RPS)
//   k6 run -e ENDPOINT=get-itinerary  -e MODE=vus -e VUS=400 -e DURATION=20s scripts/load/stress-endpoint.js (VU)
//
// 제외(정당 사유): POST /chat-messages(AI 별도 평가), DELETE(반복마다 새 타깃 필요→rate 부적합),
//   POST/DELETE /users(멱등 no-op / Clerk 계정 파괴).
import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL } from './lib/config.js';
import { getToken, cleanup, purgeReservations, params } from './lib/common.js';
import { newRoomPayload } from './lib/payloads.js';

const EP = __ENV.ENDPOINT || 'get-itinerary';
const MODE = (__ENV.MODE || 'rps').toLowerCase(); // 'rps' | 'vus'
const rate = __ENV.RATE ? Number(__ENV.RATE) : 500;
const vus = __ENV.VUS ? Number(__ENV.VUS) : 100;
const duration = __ENV.DURATION || (MODE === 'vus' ? '20s' : '15s');
const POOL = __ENV.POOL ? Number(__ENV.POOL) : 100;
const SEED_DATE = '2026-05-01';

// 리소스 경합을 분산해야 하는(=특정 행을 수정하는) 엔드포인트
const PATCH_RESOURCE = new Set([
  'patch-name', 'patch-itinerary', 'patch-status', 'patch-dayplans', 'patch-item-status', 'patch-reservation',
]);
let dpCounter = 0; // VU별 카운터 — PATCH 시 매번 다른 값을 보내 "No changes detected"(400) 회피
const isPatch = PATCH_RESOURCE.has(EP);
// 수정 엔드포인트는 "동시 작업자 수" 이상의 리소스 풀이 필요(VU↔리소스 1:1 → 행 경합 방지).
// VU 모드에선 동시 작업자 = VUS, RPS 모드에선 maxVUs를 풀 크기로 캡해 1:1 유지.
const concurrency = MODE === 'vus' ? vus : 0;
const poolSize = isPatch ? Math.max(POOL, concurrency) : 1;
const maxVUs = isPatch ? poolSize : 3000;
const preAllocatedVUs = isPatch ? poolSize : Math.max(50, Math.ceil(rate / 5));

// 모드별 executor 구성
const scenario = MODE === 'vus'
  ? { executor: 'constant-vus', vus, duration }
  : { executor: 'constant-arrival-rate', rate, timeUnit: '1s', duration, preAllocatedVUs, maxVUs };

// 측정 대상 요청의 name 태그 — teardown(정리 DELETE)과 분리해 순수 지표를 뽑기 위함.
const NAME_BY_EP = {
  'get-chatrooms': 'GET /chat-rooms', 'get-chatroom': 'GET /chat-rooms/{id}',
  'get-itineraries': 'GET /itineraries', 'get-itinerary': 'GET /itineraries/{id}',
  'get-logs': 'GET /itineraries/{id}/logs', 'get-reservations': 'GET /reservations',
  'post-chatroom': 'POST /chat-rooms', 'post-reservation': 'POST /reservations',
  'patch-name': 'PATCH /chat-rooms/{id}/name', 'patch-itinerary': 'PATCH /itineraries/{id}',
  'patch-status': 'PATCH /itineraries/{id}/status', 'patch-dayplans': 'PATCH /itineraries/{id}/day-plans',
  'patch-item-status': 'PATCH /itineraries/{id}/items/status', 'patch-reservation': 'PATCH /reservations/{id}',
};
const ACTION = NAME_BY_EP[EP];

export const options = {
  scenarios: { ep: scenario },
  // 액션 요청만 골라내는 하위지표(항상 통과) → summary-export에 순수 p95/count가 노출된다.
  thresholds: {
    http_req_failed: ['rate<0.01'],
    [`http_req_duration{name:${ACTION}}`]: ['p(95)>=0'],
    [`http_reqs{name:${ACTION}}`]: ['count>=0'],
    [`http_req_failed{name:${ACTION}}`]: ['rate>=0'],
  },
};

function reservationBody(itineraryId) {
  return {
    itineraryId, type: 'flight', bookedBy: 'ai',
    bookingUrl: 'https://booking.example.com/flight/123', externalRefId: 'KE12345678',
    detail: {
      airline: '대한항공', flight_no: 'KE123',
      departure: { airport: 'ICN', datetime: '2026-05-01T09:00:00' },
      arrival: { airport: 'NRT', datetime: '2026-05-01T11:30:00' },
      seat_class: 'economy',
      passengers: [{ name: '홍길동', passport: 'M12345678' }],
    },
    totalPrice: 320000.0, currency: 'KRW', reservedAt: '2026-04-03T21:20:00+09:00',
  };
}

export function setup() {
  const token = getToken();
  const p = params(token);
  http.post(`${BASE_URL}/api/v1/users/signup`, null, p);

  const needItem = EP === 'patch-item-status';
  const needResv = EP === 'patch-reservation';

  const pool = [];
  for (let i = 0; i < poolSize; i++) {
    const room = http.post(`${BASE_URL}/api/v1/chat-rooms`, JSON.stringify(newRoomPayload()), p).json();
    const entry = { roomId: room.roomId, itineraryId: room.itineraryId, reservationId: null };
    if (needItem) {
      http.patch(`${BASE_URL}/api/v1/itineraries/${entry.itineraryId}/day-plans`,
        JSON.stringify({ dayPlans: { [SEED_DATE]: [
          { plan_name: '시드', time: '09:00 ~ 10:00', place: '장소', note: '', cost: null },
        ] } }), p);
    }
    if (needResv) {
      const rv = http.post(`${BASE_URL}/api/v1/reservations`, JSON.stringify(reservationBody(entry.itineraryId)), p);
      entry.reservationId = rv.status === 201 ? rv.json('reservationId') : null;
    }
    pool.push(entry);
  }
  return { token, pool };
}

export function teardown(data) {
  data.pool.forEach((e) => purgeReservations(e.itineraryId)); // 예약 먼저(FK RESTRICT)
  cleanup(data.token); // 채팅방(+cascade) 전량 제거 (생성 스트레스분 포함)
}

export default function (data) {
  const { token, pool } = data;
  const e = pool[(__VU - 1) % pool.length]; // VU마다 다른 리소스
  let res, ok;

  switch (EP) {
    // ---- 읽기 ----
    case 'get-chatrooms': res = http.get(`${BASE_URL}/api/v1/chat-rooms`, params(token, 'GET /chat-rooms')); ok = 200; break;
    case 'get-chatroom': res = http.get(`${BASE_URL}/api/v1/chat-rooms/${e.roomId}`, params(token, 'GET /chat-rooms/{id}')); ok = 200; break;
    case 'get-itineraries': res = http.get(`${BASE_URL}/api/v1/itineraries`, params(token, 'GET /itineraries')); ok = 200; break;
    case 'get-itinerary': res = http.get(`${BASE_URL}/api/v1/itineraries/${e.itineraryId}`, params(token, 'GET /itineraries/{id}')); ok = 200; break;
    case 'get-logs': res = http.get(`${BASE_URL}/api/v1/itineraries/${e.itineraryId}/logs`, params(token, 'GET /itineraries/{id}/logs')); ok = 200; break;
    case 'get-reservations': res = http.get(`${BASE_URL}/api/v1/reservations`, params(token, 'GET /reservations')); ok = 200; break;

    // ---- 쓰기(생성) — 경합 없음 ----
    case 'post-chatroom': res = http.post(`${BASE_URL}/api/v1/chat-rooms`, JSON.stringify(newRoomPayload()), params(token, 'POST /chat-rooms')); ok = 201; break;
    case 'post-reservation': res = http.post(`${BASE_URL}/api/v1/reservations`, JSON.stringify(reservationBody(e.itineraryId)), params(token, 'POST /reservations')); ok = 201; break;

    // ---- 쓰기(수정) — 풀로 리소스 분산 ----
    case 'patch-name': res = http.patch(`${BASE_URL}/api/v1/chat-rooms/${e.roomId}/name`, JSON.stringify({ name: `n${Date.now()}` }), params(token, 'PATCH /chat-rooms/{id}/name')); ok = 200; break;
    case 'patch-itinerary': res = http.patch(`${BASE_URL}/api/v1/itineraries/${e.itineraryId}`, JSON.stringify({ budget: 300000 + dpCounter++, adultCount: 2, childCount: 0, childAges: [] }), params(token, 'PATCH /itineraries/{id}')); ok = 200; break;
    case 'patch-status': res = http.patch(`${BASE_URL}/api/v1/itineraries/${e.itineraryId}/status`, JSON.stringify({ status: (dpCounter++ % 2 === 0) ? 'completed' : 'draft' }), params(token, 'PATCH /itineraries/{id}/status')); ok = 200; break;
    case 'patch-dayplans': res = http.patch(`${BASE_URL}/api/v1/itineraries/${e.itineraryId}/day-plans`, JSON.stringify({ dayPlans: { [SEED_DATE]: [
      { plan_name: `수정 ${__VU}-${dpCounter++}`, time: '11:00 ~ 12:00', place: '장소', note: '', cost: null },
    ] } }), params(token, 'PATCH /itineraries/{id}/day-plans')); ok = 200; break;
    case 'patch-item-status': res = http.patch(`${BASE_URL}/api/v1/itineraries/${e.itineraryId}/items/status`, JSON.stringify({ date: SEED_DATE, index: 0, status: (dpCounter++ % 2 === 0) ? 'done' : 'todo' }), params(token, 'PATCH /itineraries/{id}/items/status')); ok = 200; break;
    case 'patch-reservation': res = http.patch(`${BASE_URL}/api/v1/reservations/${e.reservationId}`, JSON.stringify({ totalPrice: 300000 + dpCounter++ }), params(token, 'PATCH /reservations/{id}')); ok = 200; break;

    default: throw new Error(`unknown ENDPOINT=${EP}`);
  }
  check(res, { [`${ok}`]: (r) => r.status === ok });
}
