// 공통 설정 — 환경 변수로 오버라이드 가능
//   BASE_URL   대상 서버 (기본: http://localhost:8080)
//   TOKEN      미리 발급받은 Clerk JWT (있으면 /dev/auth/token 호출 생략)
//   CLERK_ID   dev 토큰 발급에 사용할 clerkId (TOKEN 미지정 시)
//   VUS        가상 사용자 수 오버라이드 (시나리오별 기본값 대체)
//   DURATION   부하 지속 시간 오버라이드 (예: 1m, 30s)

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const CLERK_ID = __ENV.CLERK_ID || 'load-test-user';
export const STATIC_TOKEN = __ENV.TOKEN || '';

export const VUS = __ENV.VUS ? Number(__ENV.VUS) : null;
export const DURATION = __ENV.DURATION || null;

// 결과 지표 기준값(Threshold)
//   - 읽기/쓰기 API: p95 < 500ms, p99 < 1000ms
//   - 전체 실패율 1% 미만
// 위반 시 k6는 종료 코드 99로 실패 처리 → CI 게이트로 활용 가능
export const thresholds = {
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<500', 'p(99)<1000'],
  checks: ['rate>0.99'],
};
