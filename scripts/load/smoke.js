// 스모크 테스트 — VU 1로 주요 엔드포인트를 한 번씩 호출해 환경/인증이 정상인지 확인.
// 부하 측정용이 아니라 "K6 설치 및 로컬 실행 환경 확인" 용도.
//
// 실행: k6 run scripts/load/smoke.js
import { check, group } from 'k6';
import http from 'k6/http';
import { BASE_URL } from './lib/config.js';
import { bootstrap, cleanup, params } from './lib/common.js';

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1.0'], // 스모크는 전부 통과해야 한다
    http_req_failed: ['rate==0'],
  },
};

export function setup() {
  return bootstrap();
}

// 테스트 종료 후 생성된 픽스처 채팅방/일정 자동 삭제
export function teardown(data) {
  cleanup(data.token);
}

export default function (data) {
  const { token, roomId, itineraryId } = data;

  group('chat-rooms', () => {
    const list = http.get(`${BASE_URL}/api/v1/chat-rooms`, params(token, 'GET /chat-rooms'));
    check(list, { '목록 200': (r) => r.status === 200 });

    const detail = http.get(`${BASE_URL}/api/v1/chat-rooms/${roomId}`, params(token, 'GET /chat-rooms/{id}'));
    check(detail, { '상세 200': (r) => r.status === 200 });
  });

  group('itineraries', () => {
    const list = http.get(`${BASE_URL}/api/v1/itineraries`, params(token, 'GET /itineraries'));
    check(list, { '목록 200': (r) => r.status === 200 });

    const detail = http.get(`${BASE_URL}/api/v1/itineraries/${itineraryId}`, params(token, 'GET /itineraries/{id}'));
    check(detail, { '상세 200': (r) => r.status === 200 });

    const logs = http.get(`${BASE_URL}/api/v1/itineraries/${itineraryId}/logs`, params(token, 'GET /itineraries/{id}/logs'));
    check(logs, { '이력 200': (r) => r.status === 200 });
  });

  group('reservations', () => {
    const list = http.get(`${BASE_URL}/api/v1/reservations`, params(token, 'GET /reservations'));
    check(list, { '목록 200': (r) => r.status === 200 });
  });
}
