// 공통 유틸 — 인증 토큰 발급, 요청 파라미터 생성, 부트스트랩(테스트 픽스처 준비)
import http from 'k6/http';
import { fail } from 'k6';
import { BASE_URL, STATIC_TOKEN, CLERK_ID } from './config.js';
import { newRoomPayload } from './payloads.js';

// 인증 토큰 확보
//   - TOKEN 환경변수가 있으면 그대로 사용 (실제 Clerk JWT 등)
//   - 없으면 dev 프로파일 전용 "dev-{clerkId}" 토큰 사용.
//     DevSecurityConfig 의 커스텀 디코더가 Clerk API 호출 없이 즉시 인증하며,
//     subject 를 CLERK_ID 로 설정한다. → 로컬 부하 테스트 기본 경로.
export function getToken() {
  if (STATIC_TOKEN) return STATIC_TOKEN;
  return `dev-${CLERK_ID}`;
}

// 인증 헤더 + 지표 태그(name)를 담은 요청 파라미터 생성
export function params(token, name) {
  return {
    headers: {
      Authorization: `Bearer ${token}`,
      'Content-Type': 'application/json',
    },
    tags: name ? { name } : {},
  };
}

// teardown() 단계에서 1회 실행 — 테스트가 생성한 채팅방(+cascade로 일정/메시지)을 전부 삭제.
// 목록을 가져와 남은 방이 없을 때까지 반복 삭제한다. clerkId=CLERK_ID 소유 데이터만 대상.
// 삭제 병렬도 — 한 라운드에서 http.batch로 동시에 쏘는 DELETE 개수.
//   순차 삭제는 개당 왕복이라 대량(수만 건) 시 teardown이 수백 초 → 타임아웃. 병렬로 분/초 단위로 단축.
//   실효 동시성은 options.batchPerHost 로도 제한되므로 stress-endpoint.js/cleanup.js에서 함께 올린다.
const DELETE_BATCH = 40;

export function cleanup(token) {
  // 채팅방을 목록 조회 → http.batch로 병렬 DELETE(cascade로 일정/메시지 동반 삭제)해 전부 지운다.
  //   ⚠️ signout(유저 삭제)은 이 환경에서 chat_rooms를 cascade 삭제하지 않는다(실측). 실제 정리는
  //      이 삭제 루프가 담당한다. 남은 방은 라운드마다 다시 조회해 처리하므로 일부 실패도 재시도된다.
  const listParams = params(token, 'GET /chat-rooms');
  const delParams = params(token, 'DELETE /chat-rooms/{id}');
  let deleted = 0;

  for (let round = 0; round < 2000; round++) {
    const res = http.get(`${BASE_URL}/api/v1/chat-rooms`, listParams);
    if (res.status !== 200) {
      console.error(`[teardown] 목록 조회 실패 status=${res.status} — 정리 중단`);
      break;
    }
    const rooms = res.json('rooms') || [];
    if (rooms.length === 0) break;

    // DELETE_BATCH개씩 묶어 http.batch로 동시 삭제
    for (let i = 0; i < rooms.length; i += DELETE_BATCH) {
      const reqs = rooms.slice(i, i + DELETE_BATCH).map((room) => ({
        method: 'DELETE',
        url: `${BASE_URL}/api/v1/chat-rooms/${room.roomId}`,
        params: delParams,
      }));
      const responses = http.batch(reqs);
      for (const r of responses) if (r.status === 200) deleted++;
    }
  }

  // 테스트 유저 행 정리(setup의 signup으로 만든 dev 가짜 유저). Clerk 404는 성공 처리됨.
  const out = http.del(`${BASE_URL}/api/v1/users/signout`, null, params(token, 'DELETE /users/signout'));
  const userDeleted = out.status === 204 || out.status === 200;
  if (!userDeleted) console.error(`[teardown] 테스트 유저 삭제 실패 status=${out.status} body=${out.body}`);

  console.log(`[teardown] 채팅방 ${deleted}건 삭제 + 테스트 유저 삭제(${userDeleted ? 'OK' : 'FAIL'}, clerkId=${CLERK_ID})`);
}

// 예약 정리 — dev 벌크 삭제 엔드포인트로 해당 일정의 모든 예약 제거(FK RESTRICT 회피용).
// 예약 생성 스트레스 후 채팅방 삭제 전에 호출한다.
export function purgeReservations(itineraryId) {
  http.del(`${BASE_URL}/dev/reservations/seed/${itineraryId}`);
}

// setup() 단계에서 1회 실행 — 토큰 발급, 유저 미러링, 읽기 시나리오용 채팅방/일정 픽스처 생성
export function bootstrap() {
  const token = getToken();

  // chat_rooms.clerk_id 는 users FK — 먼저 회원가입(멱등)으로 유저 행 보장
  const signup = http.post(`${BASE_URL}/api/v1/users/signup`, null, params(token, 'POST /users/signup'));
  if (signup.status !== 200) {
    fail(`회원가입(signup) 실패: status=${signup.status} body=${signup.body}`);
  }

  // 읽기 시나리오(상세 조회)에 사용할 roomId / itineraryId 확보
  const res = http.post(
    `${BASE_URL}/api/v1/chat-rooms`,
    JSON.stringify(newRoomPayload()),
    params(token, 'POST /chat-rooms'),
  );
  if (res.status !== 201) {
    fail(`픽스처 채팅방 생성 실패: status=${res.status} body=${res.body}`);
  }
  const room = res.json();

  return { token, roomId: room.roomId, itineraryId: room.itineraryId };
}
