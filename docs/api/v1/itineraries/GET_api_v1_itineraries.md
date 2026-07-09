## **[GET] /api/v1/itineraries**

내 여행 일정 목록을 조회합니다.

---

### **1. 기본 정보**

| 항목 | 내용 |
| --- | --- |
| Method | `GET` |
| URL | `/api/v1/itineraries` |
| Summary | 내 여행 일정 목록 조회 |
| Authentication | Bearer JWT (Clerk 발급 토큰 필수) |

---

### **2. 요청 (Request)**

#### **2.1 Headers**

| Name | Required | Example | Description |
| --- | --- | --- | --- |
| Authorization | Y | `Bearer eyJhbGci...` | Clerk에서 발급받은 유효한 JWT 토큰 |

---

### **3. 응답 (Response)**

#### **3.1 성공 (200 OK)**

- **Description**: 일정 목록이 성공적으로 조회되었습니다.

```json
{
  "itineraries": [
    {
      "itineraryId": "aaa-111",
      "name": "당일치기 서울 여행",
      "status": "draft",
      "origin": {"city": "인천"},
      "destinations": [{"city": "서울", "start_date": "2026-04-15", "end_date": "2026-04-15"}],
      "totalDays": 1,
      "startDate": "2026-04-15"
    },
    {
      "itineraryId": "bbb-222",
      "name": "3박 4일 제주 여행",
      "status": "draft",
      "origin": {"city": "서울"},
      "destinations": [{"city": "제주", "start_date": "2026-05-10", "end_date": "2026-05-13"}],
      "totalDays": 4,
      "startDate": "2026-05-10"
    },
    {
      "itineraryId": "ccc-333",
      "name": "2박 3일 부산 여행",
      "status": "completed",
      "origin": {"city": "서울"},
      "destinations": [{"city": "부산", "start_date": "2026-03-20", "end_date": "2026-03-22"}],
      "totalDays": 3,
      "startDate": "2026-03-20"
    }
  ]
}
```

**응답 필드 설명** (`itineraries` 배열 아이템 필드)

| Field | Type | Description |
| --- | --- | --- |
| `itineraryId` | UUID | 일정 고유 ID (`itineraries.id`) |
| `name` | String | 채팅방 이름 (`chat_rooms.name`) |
| `status` | String | 일정 상태 (`draft` / `completed`) |
| `origin` | Object | 출발지 정보 (`{"city": "..."}`) |
| `destinations` | Array | 여행지 목록 (`[{"city": "...", "start_date": "...", "end_date": "..."}]`) |
| `totalDays` | Integer | 총 여행 일수 |
| `startDate` | String (YYYY-MM-DD) | 여행 시작일 |

#### **3.2 인증 실패 (401 Unauthorized)**

- **Description**: JWT 토큰이 누락되었거나 만료, 서명 오류 등으로 유효하지 않은 경우입니다.

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid or expired token."
}
```

#### **3.3 사용자 없음 (404 Not Found)**

- **Description**: JWT는 유효하지만 서비스 DB에 해당 사용자가 존재하지 않는 경우입니다.

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "User not found. Please sign up first."
}
```

---

### **4. 비즈니스 로직 및 DB 스키마**

#### **4.1 동작 흐름 (Sequence)**

1. **Token Parsing**: Authorization 헤더에서 JWT를 추출하고 검증합니다.
2. **Claim Extraction**: JWT의 `sub` 클레임을 추출하여 `clerk_id`로 사용합니다.
3. **User Check**: `users` 테이블에서 해당 `clerk_id` 사용자가 존재하는지 확인합니다. 존재하지 않으면 404를 반환합니다.
4. **Query**: `chat_rooms.clerk_id`가 요청자의 `clerk_id`와 일치하는 itineraries를 조회합니다. `chat_rooms.name`을 조인하여 함께 반환합니다.
5. **Sort**: `status = draft` 우선, 동일 status 내 `start_date` 오름차순으로 정렬합니다.
6. **Response**: 조회한 일정 목록을 반환합니다. 일정이 없는 경우 빈 배열을 반환합니다. `endDate`는 목록 UI에서 표시하지 않으므로 응답에서 제외합니다 (프론트엔드에서 필요 시 `startDate + totalDays - 1`로 계산 가능).

#### **4.2 DB 조회 구조**

***itineraries Table***

| Column | Type | Description |
| --- | --- | --- |
| `id` | UUID | 일정 고유 ID |
| `room_id` | UUID | 연결된 채팅방 ID (chat_rooms 조인 키) |
| `status` | VARCHAR(20) | 일정 상태 (`draft` / `completed`) |
| `origin` | JSONB | 출발지 정보 (`{"city": "..."}`) |
| `destinations` | JSONB | 여행지 목록 (직렬화된 `DestinationItem` 배열) |
| `total_days` | INT | 총 여행 일수 |
| `start_date` | DATE | 여행 시작일 |

***chat_rooms Table (조인)***

| Column | Type | Description |
| --- | --- | --- |
| `id` | UUID | 채팅방 고유 ID |
| `clerk_id` | VARCHAR(255) | 소유자 식별 및 인증에 사용 |
| `name` | VARCHAR(100) | 채팅방 이름 |

---

### **5. 호출 예시 (Example)**

```bash
curl -X GET https://your-api-domain.com/api/v1/itineraries \
  -H "Authorization: Bearer <clerk_jwt_token>"
```