// 수동 정리 스크립트 — 부하테스트 유저(CLERK_ID) 소유 채팅방을 전부 삭제한다.
// 각 스크립트의 teardown()이 자동 정리하지만, 중단된 실행 등으로 데이터가 남았을 때 사용.
//
// 실행: k6 run scripts/load/cleanup.js
//       k6 run -e CLERK_ID=load-test-user scripts/load/cleanup.js
import { getToken, cleanup } from './lib/common.js';

export const options = {
  vus: 1,
  iterations: 1,
};

export default function () {
  cleanup(getToken());
}
