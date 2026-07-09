## **[POST] /api/v1/chat-rooms**

사용자의 새로운 여행 계획용 채팅방을 생성합니다.

---

### **1. 기본 정보**

| 항목 | 내용 |
| --- | --- |
| Method | `POST` |
| URL | `/api/v1/chat-rooms` |
| Summary | 채팅방 생성 |
| Authentication | Bearer JWT (Clerk 발급 토큰 필수) |

---

### **2. 요청 (Request)**

#### **2.1 Headers**

| Name | Required | Example | Description |
| --- | --- | --- | --- |
| Authorization | Y | `Bearer eyJhbGci...` | Clerk에서 발급받은 유효한 JWT 토큰 |

#### **2.2 Body**

```json
{
  "origin": {"city": "서울"},
  "destinations": [
    {"city": "제주도", "start_date": "2026-05-01", "end_date": "2026-05-03"}
  ],
  "budget": 300000,
  "adultCount": 2,
  "childCount": 1,
  "childAges": [7]
}
```

| Field | Required | Type | Description |
| --- | --- | --- | --- |
| `origin` | Y | Object | 출발지 정보 |
| `origin.city` | Y | String | 출발지 이름 (공백 불가) |
| `destinations` | Y | Array | 여행지 목록. 1개 이상이어야 하며, 여러 목적지일 경우 날짜가 연속적(앞 항목의 `end_date` = 다음 항목의 `start_date`)이어야 함 |
| `destinations[].city` | Y | String | 여행지 이름 |
| `destinations[].start_date` | Y | DATE | 해당 여행지 방문 시작일 (형식: YYYY-MM-DD) |
| `destinations[].end_date` | Y | DATE | 해당 여행지 방문 종료일 (형식: YYYY-MM-DD). `start_date`와 같거나 이후여야 함 (당일치기 시 `start_date`와 동일한 날짜 허용) |
| `budget` | N | Decimal | 예산 |
| `adultCount` | Y | Int | 성인 수 (최솟값: 1) |
| `childCount` | Y | Int | 아이 수 (최솟값: 0). `childAges`와 항상 함께 전달해야 함 |
| `childAges` | Y | Int[] | 아이 나이 배열. `childCount`와 항상 함께 전달해야 함. 배열 길이는 `childCount`와 일치해야 하며, `childCount`가 0이면 빈 배열(`[]`) |

---

### **3. 응답 (Response)**

#### **3.1 성공 (201 Created)**

- **Description**: 채팅방이 성공적으로 생성되었습니다.

```json
{
  "roomId": "9d12a7e5-2a7b-4e86-bf5c-2d1b50dcb1a4",
  "name": "2박 3일 제주도 여행",
  "itineraryId": "aaa-111",
  "clerkId": "user_2N...",
  "createdAt": "2026-04-03T22:00:00",
  "updatedAt": "2026-04-03T22:00:00"
}
```

#### **3.2 잘못된 요청 (400 Bad Request)**

**`origin`이 누락됨**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "origin is required."
}
```

**`origin.city`가 비어 있음**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "origin.city must not be blank."
}
```

**`destinations`가 비어 있거나 누락됨**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "destinations must contain at least one item."
}
```

**`destinations` 항목의 `city`가 비어 있음**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Each destination must have a non-blank city name."
}
```

**`destinations` 항목의 `startDate` 또는 `endDate`가 null**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Each destination must have startDate and endDate."
}
```

**`destinations` 항목의 `startDate`가 `endDate`보다 이후임**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Each destination's startDate must not be after endDate. city=제주도"
}
```

**`destinations` 항목 간 날짜가 연속적이지 않음**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Destination dates must be consecutive: destinations[0].endDate must equal destinations[1].startDate."
}
```

**`adultCount`가 1 미만**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "adultCount must be at least 1."
}
```

**`childAges` 배열 길이가 `childCount`와 불일치**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "childAges length must match childCount."
}
```

#### **3.3 인증 실패 (401 Unauthorized)**

- **Description**: JWT 토큰이 누락되었거나 만료, 서명 오류 등으로 유효하지 않은 경우입니다.

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid or expired token."
}
```

#### **3.4 리소스 없음 (404 Not Found)**

- **Description**: 서비스 DB에 해당 사용자가 존재하지 않는 경우입니다. 먼저 회원가입 동기화 API를 호출해야 합니다.

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "User not found. Please sign up first."
}
```

#### **3.5 서버 오류 (500 Internal Server Error)**

- **Description**: 채팅방 생성 중 서버 내부 오류가 발생한 경우입니다.

```json
{
  "status": 500,
  "error": "Internal Server Error",
  "message": "Failed to create chat room."
}
```

---

### **4. 비즈니스 로직 및 DB 스키마**

#### **4.1 동작 흐름 (Sequence)**

1. **Token Parsing**: Authorization 헤더에서 JWT를 추출하고 검증합니다.
2. **Claim Extraction**: JWT의 `sub` 클레임을 추출하여 `clerk_id`로 사용합니다.
3. **User Check**: `users` 테이블에서 해당 `clerk_id` 사용자가 존재하는지 확인합니다. 존재하지 않으면 404를 반환합니다.
4. **Validation**: 아래 항목을 검증합니다. 위반 시 400을 반환합니다.
   - `origin`이 null이거나 `origin.city`가 비어 있으면 400을 반환합니다.
   - `destinations`가 null이거나 비어 있으면 400을 반환합니다.
   - 각 `destinations` 항목의 `city`가 비어 있거나, `startDate`/`endDate`가 null이거나, `startDate`가 `endDate`보다 이후이면 400을 반환합니다. (`startDate`와 `endDate`가 같으면 당일치기로 허용)
   - 여러 목적지인 경우, 앞 항목의 `endDate`와 다음 항목의 `startDate`가 같지 않으면(날짜 연속성 위반) 400을 반환합니다.
   - `adultCount`가 1 미만이면 400을 반환합니다.
   - `childAges` 배열 길이가 `childCount`와 일치하지 않으면 400을 반환합니다.
5. **Insert chat_rooms**: `chat_rooms` 테이블에 새 채팅방을 생성합니다.
6. **Insert itineraries**: `itineraries` 테이블에 여행 일정을 초기화합니다. `day_plans`는 `startDate`~`endDate` 범위의 날짜를 키로, 빈 배열을 값으로 가지는 JSONB 객체로 초기화합니다.
7. **Return**: 생성된 `roomId`, `itineraryId` 및 시각 정보를 반환합니다.

#### **4.2 DB 저장 구조**

**`chat_rooms` Table**

| Column | Value |
| --- | --- |
| `id` | PK |
| `clerk_id` | JWT `sub` 클레임 |
| `name` | 자동 생성: 전체 여행 기간(마지막 목적지 `end_date` - 첫 번째 목적지 `start_date` + 1)이 1일(당일치기)이면 `"당일치기 {destinations[0].city} 여행"`, 그 외에는 `"{N-1}박 {N}일 {destinations[0].city} 여행"` |
| `ai_summary` | `NULL` |
| `preferences` | `NULL` |
| `created_at` | 생성 일자 |
| `updated_at` | 업데이트 일자 |

**`itineraries` Table**

| Column | Value |
| --- | --- |
| `id` | PK |
| `room_id` | 생성된 `chat_rooms.id` |
| `origin` | 요청 `origin` JSONB 객체 (`{"city": "..."}`) |
| `destinations` | 요청 `destinations` JSONB 배열 |
| `start_date` | `destinations[0].start_date` |
| `end_date` | `destinations[마지막].end_date` |
| `total_days` | `end_date - start_date + 1` 계산 |
| `budget` | 요청 `budget` (미포함 시 `NULL`) |
| `adult_count` | 요청 `adultCount` |
| `child_count` | 요청 `childCount` |
| `child_ages` | 요청 `childAges` |
| `status` | `'draft'` |
| `day_plans` | `startDate`~`endDate` 범위 날짜를 키로, 빈 배열을 값으로 초기화. 예: `{"2026-05-01": [], "2026-05-02": [], "2026-05-03": []}` |
| `created_at` | 생성 일자 |
| `updated_at` | 업데이트 일자 |

---

### **5. 호출 예시 (Example)**

```bash
curl -X POST https://your-api-domain.com/api/v1/chat-rooms \
  -H "Authorization: Bearer <clerk_jwt_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "origin": {"city": "서울"},
    "destinations": [{"city": "제주도", "start_date": "2026-05-01", "end_date": "2026-05-03"}],
    "budget": 300000,
    "adultCount": 2,
    "childCount": 1,
    "childAges": [7]
  }'
```
