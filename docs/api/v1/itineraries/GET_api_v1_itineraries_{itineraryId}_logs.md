## **[GET] /api/v1/itineraries/{itineraryId}/logs**

특정 여행 일정의 수정 이력(스냅샷) 목록을 시간순으로 조회합니다.

---

### **1. 기본 정보**

| 항목 | 내용 |
| --- | --- |
| Method | `GET` |
| URL | `/api/v1/itineraries/{itineraryId}/logs` |
| Summary | 여행 일정 수정 이력 조회 |
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

- **Description**: 수정 이력이 성공적으로 조회되었습니다. 이력이 없는 경우 빈 배열(`[]`)을 반환합니다.
- 각 항목은 **수정이 발생하기 직전 상태**의 스냅샷입니다.
- `createdAt`은 해당 스냅샷이 저장된 시각(= 수정이 발생한 시각)이며, **내림차순(최신 순)** 으로 정렬됩니다.

```json
{
  "itineraryId": "aaa-111",
  "logs": [
    {
      "logId": "log-001",
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
          {"plan_name": "경복궁 방문", "time": "09:00 ~ 12:00", "place": "경복궁", "note": "한복 대여 추천", "cost": {"amount": 3000, "currency": "KRW", "amount_krw": null}, "image_url": "http://tong.visitkorea.or.kr/cms/resource/.../gyeongbokgung.jpg", "url": null, "status": "done"},
          {"plan_name": "광장시장 점심", "time": "12:00 ~ 14:30", "place": "광장시장", "note": "", "cost": null, "image_url": null, "url": null, "status": "todo"}
        ]
      },
      "createdAt": "2026-04-03T20:00:00"
    },
    {
      "logId": "log-002",
      "destinations": [{"city": "서울", "start_date": "2026-05-01", "end_date": "2026-05-04"}],
      "budget": 600000.00,
      "adultCount": 2,
      "childCount": 1,
      "childAges": [5],
      "totalDays": 4,
      "startDate": "2026-05-01",
      "endDate": "2026-05-04",
      "dayPlans": {
        "2026-05-01": [
          {"plan_name": "경복궁 방문", "time": "09:00 ~ 12:00", "place": "경복궁", "note": "한복 대여 추천", "cost": {"amount": 3000, "currency": "KRW", "amount_krw": null}, "image_url": "http://tong.visitkorea.or.kr/cms/resource/.../gyeongbokgung.jpg", "url": null, "status": "done"},
          {"plan_name": "광장시장 점심", "time": "12:00 ~ 14:30", "place": "광장시장", "note": "", "cost": null, "image_url": null, "url": null, "status": "todo"},
          {"plan_name": "창덕궁 방문", "time": "14:30 ~ 18:00", "place": "창덕궁", "note": "후원 투어 예약 필요", "cost": {"amount": 3000, "currency": "KRW", "amount_krw": null}, "image_url": null, "url": null, "status": "todo"}
        ]
      },
      "createdAt": "2026-04-05T14:30:00"
    }
  ]
}
```

**응답 필드 설명** (→ `itinerary_logs` 테이블 컬럼 기준)

`itinerary_logs` 테이블의 행을 `created_at` 내림차순(최신 순)으로 정렬하여 `logs` 배열로 반환합니다. 이력이 없으면 `logs: []`입니다.

| Field | DB Column | Type | Nullable | Description |
| --- | --- | --- | --- | --- |
| `logId` | `id` | UUID | N | 로그 고유 ID |
| `destinations` | `destinations` | Array | Y | 수정 전 여행지 목록 스냅샷 |
| `budget` | `budget` | Number | Y | 수정 전 예산 스냅샷 |
| `adultCount` | `adult_count` | Integer | Y | 수정 전 어른 수 스냅샷 |
| `childCount` | `child_count` | Integer | Y | 수정 전 아이 수 스냅샷 |
| `childAges` | `child_ages` | Array | Y | 수정 전 아이 나이 목록 스냅샷 |
| `totalDays` | `total_days` | Integer | Y | 수정 전 총 여행 일수 스냅샷 |
| `startDate` | `start_date` | String (YYYY-MM-DD) | Y | 수정 전 여행 시작일 스냅샷 |
| `endDate` | `end_date` | String (YYYY-MM-DD) | Y | 수정 전 여행 종료일 스냅샷 |
| `dayPlans` | `day_plans` | Object | N | 수정 전 `day_plans` 스냅샷. DB에 저장된 원본 그대로 반환 (`index` 미포함) |
| `createdAt` | `created_at` | String (ISO 8601) | N | 스냅샷 저장 시각 (= 수정 발생 시각) |

> `destinations` ~ `endDate` 9개 필드는 `itinerary_logs` 스키마상 nullable이지만, 스냅샷 저장 시 9개 필드 전체가 항상 함께 저장되므로 실제 응답에서 `null`이 되지 않습니다.

> **`index`에 대하여**: `dayPlans` 아이템에 `index`는 포함되지 않습니다. `index`는 DB에 저장되지 않으며, 일정 상세 조회 및 아이템 상태 수정 API에서만 동적으로 부여됩니다.

> **스냅샷 대상**: `status` 변경 및 아이템 완료 상태(`todo`/`done`) 변경은 스냅샷 저장 대상이 아닙니다. 따라서 해당 수정은 이력에 남지 않습니다.

#### **3.2 인증 실패 (401 Unauthorized)**

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid or expired token."
}
```

#### **3.3 권한 없음 (403 Forbidden)**

```json
{
  "status": 403,
  "error": "Forbidden",
  "message": "You do not have permission to access this itinerary."
}
```

#### **3.4 리소스 없음 (404 Not Found)**

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Itinerary not found."
}
```

#### **3.5 사용자 없음 (404 Not Found)**

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
6. **Response**: `itinerary_logs` 테이블에서 해당 `itinerary_id`의 로그를 `created_at` 내림차순으로 조회하여 반환합니다. 이력이 없으면 빈 배열을 반환합니다.

#### **4.2 DB 조회 구조**

***itinerary_logs Table***

| Column | Type | Description |
| --- | --- | --- |
| `id` | UUID | 로그 고유 ID |
| `itinerary_id` | UUID | 대상 일정 ID (FK) |
| `destinations` | JSONB | 수정 전 여행지 목록 스냅샷 |
| `budget` | DECIMAL(12,2) | 수정 전 예산 스냅샷 |
| `adult_count` | INT | 수정 전 어른 수 스냅샷 |
| `child_count` | INT | 수정 전 아이 수 스냅샷 |
| `child_ages` | JSONB | 수정 전 아이 나이 스냅샷 |
| `total_days` | INT | 수정 전 총 여행 일수 스냅샷 |
| `start_date` | DATE | 수정 전 여행 시작일 스냅샷 |
| `end_date` | DATE | 수정 전 여행 종료일 스냅샷 |
| `day_plans` | JSONB | 수정 전 `day_plans` 스냅샷 |
| `created_at` | TIMESTAMP | 스냅샷 저장 시각 |

```sql
SELECT *
FROM itinerary_logs
WHERE itinerary_id = :itineraryId
ORDER BY created_at DESC;
```

---

### **5. 호출 예시 (Example)**

```bash
curl -X GET https://your-api-domain.com/api/v1/itineraries/{itineraryId}/logs \
  -H "Authorization: Bearer <clerk_jwt_token>"
```
