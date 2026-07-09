## **[PATCH] /api/v1/itineraries/{itineraryId}/day-plans**

날짜 단위로 여행 일정 아이템을 추가·수정합니다.

---

### **1. 기본 정보**

| 항목 | 내용 |
| --- | --- |
| Method | `PATCH` |
| URL | `/api/v1/itineraries/{itineraryId}/day-plans` |
| Summary | 여행 일정 day_plans 수정 |
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
| itineraryId | Y | UUID | 수정할 일정의 고유 ID |

#### **2.3 Body**

요청에 포함된 날짜 키의 아이템 배열만 덮어씌웁니다. 포함되지 않은 날짜는 기존 값을 유지합니다.

```json
{
  "dayPlans": {
    "2026-05-01": [
      {
        "plan_name": "경복궁 방문",
        "time": "09:00 ~ 12:00",
        "place": "경복궁",
        "note": "한복 대여 추천",
        "cost": {"amount": 3000, "currency": "KRW", "amount_krw": null}
      },
      {
        "plan_name": "광장시장 점심",
        "time": "12:00 ~ 14:30",
        "place": "광장시장",
        "note": "",
        "cost": null
      }
    ],
    "2026-05-02": []
  }
}
```

| Field | Required | Type | Description |
| --- | --- | --- | --- |
| `dayPlans` | Y | Object | 날짜(YYYY-MM-DD)를 키로 갖는 객체. 하나 이상의 날짜 키를 포함해야 함 |
| `dayPlans[date]` | Y | Array | 해당 날짜의 아이템 배열. 빈 배열(`[]`) 전달 시 해당 날짜의 기존 일정 전체 삭제 |

**아이템 필드**

| Field | Required | Type | Description |
| --- | --- | --- | --- |
| `plan_name` | Y | String | 일정 이름 |
| `time` | Y | String | 일정 시간 범위. `"HH:MM ~ HH:MM"` 형식 (24시간제, 예: `"09:00 ~ 12:00"`) |
| `place` | Y | String | 장소명 |
| `note` | N | String | 메모. 생략 시 빈 문자열(`""`)로 저장 |
| `cost` | N | Object \| null | 1인 기준 예상 비용. 무료이면 `null`. 생략 시 `null`로 저장 |
| `cost.amount` | - | Number | 현지 통화 금액 (소수점 허용) |
| `cost.currency` | - | String | ISO 4217 통화 코드 (예: `"KRW"`, `"JPY"`, `"USD"`) |
| `cost.amount_krw` | - | Integer \| null | 한화 환산 금액. `currency`가 `"KRW"`이면 `null` |
| `image_url` | N | String \| null | 대표 이미지 URL. 요청에 포함하면 보존, 생략하면 `null`로 저장 |
| `url` | N | String \| null | 예약/상세 링크. 요청에 포함하면 보존, 생략하면 `null`로 저장 |

> `status`는 요청에 포함하지 않습니다. 서버에서 자동으로 결정됩니다. 기존 `day_plans`에 동일 `time`의 아이템이 있으면 해당 `status`를 이어받고, 없는 신규 아이템은 `"todo"`로 초기화됩니다.
>
> `image_url`·`url`은 AI가 생성한 값(관광지 이미지·예약 딥링크 등)입니다. 사용자가 직접 수정 시 기존 값을 보존하려면 요청에 그대로 포함하고, 생략하면 `null`로 저장됩니다.

---

### **3. 응답 (Response)**

#### **3.1 성공 (200 OK)**

- **Description**: 요청한 날짜의 일정이 성공적으로 수정되었습니다. 전체 `dayPlans`를 반환합니다.

```json
{
  "itineraryId": "aaa-111",
  "dayPlans": {
    "2026-05-01": [
      {"plan_name": "경복궁 방문", "time": "09:00 ~ 12:00", "place": "경복궁", "note": "한복 대여 추천", "cost": {"amount": 3000, "currency": "KRW", "amount_krw": null}, "image_url": null, "url": null, "status": "done"},
      {"plan_name": "광장시장 점심", "time": "12:00 ~ 14:30", "place": "광장시장", "note": "", "cost": null, "image_url": null, "url": null, "status": "todo"}
    ],
    "2026-05-02": [],
    "2026-05-03": []
  },
  "updatedAt": "2026-04-03T22:00:00"
}
```

> `dayPlans`의 각 날짜 아이템 배열은 `time` 시작 시각 오름차순으로 정렬되어 반환됩니다.
>
> 응답의 `status`는 서버가 결정한 값입니다. 기존 아이템(동일 `time`)은 기존 `status`를 유지하고, 신규 아이템은 `"todo"`입니다.
>
> **`index`에 대하여**: `index`는 DB(`day_plans` JSONB)에 저장되지 않습니다. 아이템 위치를 식별해야 하는 API(일정 상세 조회, 아이템 상태 수정)에서만 응답 시 배열 순서 기반으로 동적으로 부여됩니다. 이 API의 요청 및 응답에는 `index`가 포함되지 않습니다.

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
  "message": "You do not have permission to update this itinerary."
}
```

#### **3.4 잘못된 요청 (400 Bad Request)**

**날짜 키가 일정 범위(`startDate` ~ `endDate`) 밖인 경우**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Date '2026-05-10' is out of the itinerary date range."
}
```

**아이템 필수 필드 누락 (`plan_name`, `time`, `place`)**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Each item must include plan_name, time, and place."
}
```

**`time` 형식이 `"HH:MM ~ HH:MM"`이 아닌 경우**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Invalid time format. Use 'HH:MM ~ HH:MM' (e.g. '09:00 ~ 12:00')."
}
```

**동일 날짜 내 `time` 범위 겹침**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "Time ranges must not overlap within the same date."
}
```

**요청에 포함된 날짜의 아이템 배열이 모두 기존 값과 동일한 경우**
```json
{
  "status": 400,
  "error": "Bad Request",
  "message": "No changes detected. The submitted day plans are identical to the current data."
}
```

#### **3.5 리소스 없음 (404 Not Found)**

```json
{
  "status": 404,
  "error": "Not Found",
  "message": "Itinerary not found."
}
```

#### **3.6 사용자 없음 (404 Not Found)**

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
6. **Validation**: 아래 항목을 순서대로 검증합니다. 위반 시 400을 반환합니다.
   - 요청의 모든 날짜 키가 `startDate` ~ `endDate` 범위 안에 있는지 확인합니다.
   - 각 아이템에 `plan_name`, `time`, `place` 필드가 모두 포함되어 있는지 확인합니다.
   - 각 아이템의 `time`이 `"HH:MM ~ HH:MM"` 형식인지 확인합니다.
   - 동일 날짜 내 아이템들의 시간 범위가 겹치지 않는지 확인합니다.
7. **Status 결정**: 각 아이템의 `status`를 요청과 무관하게 서버에서 결정합니다. 기존 `day_plans`에서 동일 날짜·동일 `time`의 아이템이 있으면 해당 `status`를 이어받고, 없는 신규 아이템은 `"todo"`로 초기화합니다.
8. **No Change Check**: 요청에 포함된 모든 날짜의 아이템 배열이 기존 값과 동일하면 400을 반환합니다. `status`는 7단계에서 결정된 값 기준으로 비교하며, `note` 누락은 빈 문자열(`""`)과 동일하게 취급하고 `cost` 누락은 `null`과 동일하게 취급합니다. 각 배열은 `time` 오름차순 정렬 후 비교합니다.
9. **Snapshot**: 수정 전 `destinations`, `budget`, `adult_count`, `child_count`, `child_ages`, `total_days`, `start_date`, `end_date`, `day_plans` 값을 `itinerary_logs` 테이블에 저장합니다.
10. **Update**: 기존 `day_plans`에서 요청에 포함된 날짜 키의 배열만 덮어씌웁니다. 각 날짜의 아이템 배열은 `time` 시작 시각 오름차순으로 정렬한 후 저장합니다. `updated_at`을 갱신합니다.
11. **Response**: 갱신된 전체 `day_plans`를 반환합니다. `index`는 포함하지 않습니다.

#### **4.2 DB 업데이트 구조**

```sql
UPDATE itineraries
SET
  day_plans = day_plans || :mergedDayPlans::jsonb,
  updated_at = NOW()
WHERE id = :itineraryId;
```

`:mergedDayPlans` = 요청에 포함된 날짜 키만 담은 JSONB 객체 (각 배열은 `time` 오름차순 정렬 후 직렬화). `||` 연산자로 기존 `day_plans`에 병합하므로 요청에 없는 날짜 키는 그대로 유지됩니다.

#### **4.3 스냅샷**

`day_plans` 변경이므로 `itinerary_logs` 스냅샷 저장 대상입니다. 9개 필드 전체를 저장합니다.

---

### **5. 호출 예시 (Example)**

```bash
curl -X PATCH https://your-api-domain.com/api/v1/itineraries/{itineraryId}/day-plans \
  -H "Authorization: Bearer <clerk_jwt_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "dayPlans": {
      "2026-05-01": [
        {"plan_name": "경복궁 방문", "time": "09:00 ~ 12:00", "place": "경복궁", "note": "한복 대여 추천", "cost": {"amount": 3000, "currency": "KRW", "amount_krw": null}},
        {"plan_name": "광장시장 점심", "time": "12:00 ~ 14:30", "place": "광장시장", "note": "", "cost": null}
      ],
      "2026-05-02": []
    }
  }'
```
