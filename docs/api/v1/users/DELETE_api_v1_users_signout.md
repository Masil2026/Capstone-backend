## **[DELETE] /api/v1/users/signout**

현재 로그인한 사용자의 계정을 탈퇴 처리합니다.

DB에서 사용자 및 모든 연관 데이터를 삭제한 뒤, Clerk에서도 계정을 삭제합니다.

---

### **1. 기본 정보**

| 항목 | 내용 |
| --- | --- |
| **Method** | `DELETE` |
| **URL** | `/api/v1/users/signout` |
| **Summary** | 회원탈퇴 |
| **Authentication** | **Bearer JWT** (Clerk 발급 토큰 필수) |
| **Domain** | USERS |

---

### **2. 요청 (Request)**

#### **2.1 Headers**

| Name | Required | Example | Description |
| --- | --- | --- | --- |
| **Authorization** | Y | `Bearer eyJhbGci...` | Clerk에서 발급받은 유효한 JWT 토큰 |

#### **2.2 Path Parameter**

- 없음

#### **2.3 Body**

- 없음

---

### **3. 응답 (Response)**

#### **3.1 성공 (204 No Content)**

- **Description**: 회원탈퇴가 성공적으로 처리되었습니다. DB 및 Clerk 계정이 모두 삭제됩니다.
- **Body**: (No Body)

#### **3.2 인증 실패 (401 Unauthorized)**

- **Description**: JWT 토큰이 누락되었거나 만료, 서명 오류 등으로 유효하지 않은 경우입니다.

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Invalid or expired token."
}
```

#### **3.3 서버 오류 (500 Internal Server Error)**

- **Description**: Clerk Backend API 호출 실패 등 서버 내부 오류가 발생한 경우입니다.

```json
{
  "status": 500,
  "error": "Internal Server Error",
  "message": "Failed to delete user from Clerk."
}
```

---

### **4. 비즈니스 로직 및 DB 스키마**

#### **4.1 동작 흐름 (Sequence)**

1. **Token Parsing**: `Authorization` 헤더에서 JWT를 추출하고 검증합니다.
2. **Claim Extraction**: JWT의 `sub` 클레임을 추출하여 `clerk_id`로 사용합니다.
3. **DB Delete (Transactional)**: `dbScheduler` 스레드풀에서 아래 작업을 수행합니다.
   - `users` 테이블에서 해당 `clerk_id` 레코드를 삭제합니다. 존재하지 않으면 삭제 없이 다음 단계로 진행합니다 (멱등성 보장).
   - 외래키 `ON DELETE CASCADE` 제약에 의해 연관 테이블의 데이터가 모두 연쇄 삭제됩니다.
4. **Clerk Delete**: Clerk Backend API(`DELETE https://api.clerk.com/v1/users/{clerkId}`)를 호출하여 Clerk 계정을 삭제합니다.
   - 응답 `200`: 정상 삭제
   - 응답 `404`: 이미 Clerk에서 삭제된 상태이므로 성공으로 간주합니다 (멱등성 보장).
   - 그 외 오류: `500 Internal Server Error` 반환

#### **4.2 트랜잭션 처리**

DB 삭제는 `TransactionTemplate`을 사용하여 단일 트랜잭션 내에서 처리됩니다. DB 삭제 성공 후 Clerk API 호출이 실패하면 `500`을 반환하며, 이 경우 DB 데이터는 이미 삭제된 상태입니다. 재시도 시 DB 삭제는 멱등적으로 처리(no-op)되고 Clerk API 삭제만 재시도됩니다.

#### **4.3 DB 삭제 구조 (`users` Table)**

| Column | Type | Constraints | Description |
| --- | --- | --- | --- |
| `clerk_id` | `VARCHAR(255)` | **Primary Key** | Clerk에서 제공하는 고유 사용자 ID |
| `created_at` | `TIMESTAMPTZ` | `DEFAULT NOW()` | 데이터 생성 일시 |

#### **4.4 Cascade 삭제 범위**

`users` 레코드 삭제 시 외래키 `ON DELETE CASCADE` 제약에 의해 아래 테이블의 데이터가 연쇄 삭제됩니다.

| 대상 테이블 | 연결 컬럼 | 삭제 방식 |
| --- | --- | --- |
| `chat_rooms` | `clerk_id` | `users` 삭제 시 함께 삭제 |
| `chat_messages` | `room_id` | 연결된 `chat_rooms` 삭제 시 함께 삭제 |
| `itineraries` | `room_id` | 연결된 `chat_rooms` 삭제 시 함께 삭제 |
| `itinerary_logs` | `itinerary_id` | 연결된 `itineraries` 삭제 시 함께 삭제 |
| `reservations` | `itinerary_id` | `ON DELETE RESTRICT` — 단, `users` cascade 경로에서는 `itineraries`가 먼저 삭제되므로 활성 예약이 있더라도 함께 삭제됨 |

---

### **5. 호출 예시 (Example)**

```bash
curl -X DELETE https://your-api-domain.com/api/v1/users/signout \
  -H "Authorization: Bearer <clerk_jwt_token>"
```
