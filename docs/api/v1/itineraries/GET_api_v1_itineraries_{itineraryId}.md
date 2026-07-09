## **[GET] /api/v1/itineraries/{itineraryId}**

여행 일정 상세 정보를 조회합니다.

---

### **1. 기본 정보**

| 항목 | 내용 |
| --- | --- |
| Method | `GET` |
| URL | `/api/v1/itineraries/{itineraryId}` |
| Summary | 여행 일정 조회 |
| Authentication | Bearer JWT (Clerk 발급 토큰 필수) |

---

### **2. 요청 (Request)**

#### **2.1 Headers**

| Name | Required | Example | Description |
| --- | --- | --- | --- |
| Authorization | Y | `Bearer eyJhbGci...` | Clerk에서 발급받은 유효한 JWT 토큰 |

#### **2.2 Path Parameter**

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| itineraryId | Y | UUID | 조회할 일정의 고유 ID |

---

### **3. 응답 (Response)**

#### **3.1 성공 (200 OK)**

- **Description**: 일정이 성공적으로 조회되었습니다.

    ```json
    {
      "itineraryId": "aaa-111",
      "name": "3박 4일 서울 여행",
      "status": "draft",
      "destinations": [{"city": "서울", "start_date": "2026-05-01", "end_date": "2026-05-04"}],
      "budget": 500000.00,
      "adultCount": 2,
      "childCount": 1,
      "childAges": [5],
      "totalDays": 4,
      "startDate": "2026-05-01",
      "endDate": "2026-05-04",
      "dayPlans": {
        "2026-05-01": [
          {"index": 0, "plan_name": "경복궁 방문", "time": "09:00 ~ 12:00", "place": "경복궁", "note": "한복 대여 추천", "cost": {"amount": 3000, "currency": "KRW", "amount_krw": null}, "image_url": "http://tong.visitkorea.or.kr/cms/resource/.../gyeongbokgung.jpg", "url": null, "status": "done"},
          ...
        ]
      },
      "createdAt": "2026-04-03T20:00:00",
      "updatedAt": "2026-04-03T22:00:00"
    }
    ```


**응답 필드 설명**

| Field | Type | Description |
| --- | --- | --- |
| `itineraryId` | UUID | 일정 고유 ID (`itineraries.id`) |
| `name` | String | 채팅방 이름 (`chat_rooms.name`) |
| `status` | String | 일정 상태 (`draft` / `completed`) |
| `destinations` | Array | 여행지 목록 (`[{"city": "...", "start_date": "...", "end_date": "..."}]`) |
| `budget` | Number \| null | 예산. 미설정 시 `null` |
| `adultCount` | Integer | 어른 수 |
| `childCount` | Integer | 아이 수 |
| `childAges` | Array | 아이 나이 목록 |
| `totalDays` | Integer | 총 여행 일수 |
| `startDate` | String (YYYY-MM-DD) | 여행 시작일 |
| `endDate` | String (YYYY-MM-DD) | 여행 종료일 |
| `dayPlans` | Object | 날짜별 일정 상세. 아이템은 `time` 오름차순 정렬, `index` 포함 |
| `createdAt` | String (ISO 8601) | 일정 생성 일시 |
| `updatedAt` | String (ISO 8601) | 마지막 수정 일시 |

#### **3.2 인증 실패 (401 Unauthorized)**

- **Description**: JWT 토큰이 누락되었거나 만료, 서명 오류 등으로 유효하지 않은 경우입니다.

    ```json
    {
      "status": 401,
      "error": "Unauthorized",
      "message": "Invalid or expired token."
    }
    ```


#### **3.3 권한 없음 (403 Forbidden)**

- **Description**: 해당 일정의 소유자가 아닌 경우입니다.

    ```json
    {
      "status": 403,
      "error": "Forbidden",
      "message": "You do not have permission to access this itinerary."
    }
    ```


#### **3.4 리소스 없음 (404 Not Found)**

- **Description**: 해당 `itineraryId`에 해당하는 일정이 존재하지 않는 경우입니다.

    ```json
    {
      "status": 404,
      "error": "Not Found",
      "message": "Itinerary not found."
    }
    ```

#### **3.5 사용자 없음 (404 Not Found)**

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
3. **User Check**: `users` 테이블에서 해당 `clerk_id`가 존재하는지 확인합니다. 존재하지 않으면 404를 반환합니다.
4. **Resource Check**: `itineraryId`로 itineraries 테이블을 조회합니다. 존재하지 않으면 404를 반환합니다.
5. **Authorization Check**: `room_id → chat_rooms.clerk_id`가 요청자의 `clerk_id`와 일치하는지 확인합니다. 일치하지 않으면 403을 반환합니다.
6. **Response**: 조회한 일정 데이터와 Step 5에서 조인한 `chat_rooms.name`을 함께 반환합니다.

#### **4.2 DB 조회 구조**

***itineraries Table***

| Column | Type | Description |
| --- | --- | --- |
| `id` | UUID | 일정 고유 ID |
| `status` | VARCHAR(20) | 일정 상태 (`draft` / `completed`) |
| `destinations` | JSONB | 여행지 목록 (`DestinationItem` 배열) |
| `budget` | DECIMAL(12,2) | 예산 |
| `adult_count` | INT | 어른 수 |
| `child_count` | INT | 아이 수 |
| `child_ages` | JSONB | 아이 나이 목록 |
| `total_days` | INT | 총 여행 일수 |
| `start_date` | DATE | 여행 시작일 |
| `end_date` | DATE | 여행 종료일 |
| `day_plans` | JSONB | 날짜별 일정 상세 |
| `created_at` | TIMESTAMP | 생성 일시 |
| `updated_at` | TIMESTAMP | 마지막 수정 일시 |

***chat_rooms Table (조인)***

| Column | Type | Description |
| --- | --- | --- |
| `id` | UUID | 채팅방 고유 ID |
| `name` | VARCHAR(100) | 채팅방 이름 |

#### 4.3 `day_plans` JSONB 구조

`day_plans`는 날짜(YYYY-MM-DD)를 키로 갖는 객체이며, 각 날짜의 값은 일정 아이템 배열입니다.

```json
{
  "2026-05-01": [
    {"plan_name": "경복궁 방문", "time": "09:00 ~ 12:00", "place": "경복궁", "note": "한복 대여 추천", "cost": {"amount": 3000, "currency": "KRW", "amount_krw": null}, "image_url": "http://tong.visitkorea.or.kr/cms/resource/.../gyeongbokgung.jpg", "url": null, "status": "done"},
    {"plan_name": "광장시장 방문", "time": "12:00 ~ 14:30", "place": "광장시장", "note": "빈대떡, 마약김밥 필수", "cost": null, "image_url": null, "url": null, "status": "todo"},
    {"plan_name": "창덕궁 방문", "time": "14:30 ~ 18:00", "place": "창덕궁", "note": "후원 투어 예약 필요", "cost": {"amount": 3000, "currency": "KRW", "amount_krw": null}, "image_url": null, "url": null, "status": "todo"},
    {"plan_name": "북촌한옥마을 방문", "time": "18:00 ~ 20:00", "place": "북촌한옥마을", "note": "저녁 산책 겸 구경", "cost": null, "image_url": null, "url": null, "status": "todo"}
  ]
}
```

**필드 설명**

| Field | Type | Required | Description |
| --- | --- | --- | --- |
| `index` | `integer` | - | 응답 시 백엔드에서 부여. DB 저장 안 함. `time` 시작 시각 오름차순 정렬 후 0부터 부여 |
| `plan_name` | `string` | Y | 일정 이름 |
| `time` | `string` | Y | 일정 시간 범위. `"HH:MM ~ HH:MM"` 형식 (24시간제, 예: `"09:00 ~ 12:00"`). 동일 날짜 내 아이템 간 시간 범위는 겹치지 않아야 함 |
| `place` | `string` | Y | 장소명 |
| `note` | `string` | N | 메모. 없으면 빈 문자열(`""`) |
| `cost` | `object \| null` | N | 1인 기준 예상 비용. 무료이면 `null` |
| `cost.amount` | `number` | - | 현지 통화 금액 (소수점 허용) |
| `cost.currency` | `string` | - | ISO 4217 통화 코드 (예: `"KRW"`, `"JPY"`, `"USD"`) |
| `cost.amount_krw` | `integer \| null` | - | 한화 환산 금액. `currency`가 `"KRW"`이면 `null` |
| `image_url` | `string \| null` | N | 대표 이미지 URL (관광지 이미지·숙소 사진·항공사 로고 등). AI가 주입, 없으면 `null` |
| `url` | `string \| null` | N | 예약/상세 링크 (항공·숙소 예약 딥링크 등). AI가 주입, 없으면 `null` |
| `status` | `string` | Y | `todo` (미완료) / `done` (완료) |

**참고**

- `budget`이 설정되지 않은 경우 `"budget": null`로 반환됩니다.
- 날짜 키는 `startDate` ~ `endDate` 범위 안에 있어야 합니다.
- 아이템 배열은 `time` 오름차순으로 DB에 저장되며, 백엔드가 `index`를 부여하여 반환합니다.

---

### **5. 호출 예시 (Example)**

```bash
curl -X GET https://your-api-domain.com/api/v1/itineraries/{itineraryId} \
  -H "Authorization: Bearer <clerk_jwt_token>"
```