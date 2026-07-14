// 요청 페이로드 샘플 — DTO / docs/api 명세와 동일한 형태를 유지한다.

// 채팅방 생성(POST /api/v1/chat-rooms) — CreateChatRoomRequest
export function newRoomPayload() {
  return {
    origin: { city: '서울' },
    destinations: [
      { city: '제주도', start_date: '2026-05-01', end_date: '2026-05-03' },
    ],
    budget: 300000,
    adultCount: 2,
    childCount: 1,
    childAges: [7],
  };
}

// 채팅방 이름 수정(PATCH /api/v1/chat-rooms/{roomId}/name)
export function renameRoomPayload() {
  return { name: `부하테스트 여행 ${Date.now()}` };
}
