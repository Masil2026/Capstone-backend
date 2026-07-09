## **[POST] /api/v1/chat-messages/{roomId}**

사용자 메시지를 전송하고 AI Agent의 응답을 스트리밍으로 반환합니다.

---

### **1. 기본 정보**

| 항목 | 내용 |
| --- | --- |
| Method | `POST` |
| URL | `/api/v1/chat-messages/{roomId}` |
| Summary | 메시지 전송 및 AI Agent 응답 스트리밍 |
| Authentication | Bearer JWT (Clerk 발급 토큰 필수) |
| Response Type | `text/event-stream` (SSE) |

---

### **2. 요청 (Request)**

#### **2.1 Headers**

| Name | Required | Example | Description |
| --- | --- | --- | --- |
| Authorization | Y | `Bearer eyJhbGci...` | Clerk에서 발급받은 유효한 JWT 토큰 |

#### **2.2 Path Parameter**

| Name | Required | Type | Description |
| --- | --- | --- | --- |
| roomId | Y | UUID | 메시지를 전송할 채팅방의 고유 ID |

#### **2.3 Body**

```json
{
  "content": "경복궁 대신 창덕궁으로 바꿔줘"
}
```

| Field | Required | Type | Description |
| --- | --- | --- | --- |
| content | Y | String | 사용자가 전송한 메시지 본문 |

---

### **3. 응답 (Response)**

#### **3.1 성공 - 스트리밍 청크 (200 OK, SSE)**

- **Description**: AI Agent가 토큰 단위로 응답을 스트리밍합니다. 스트리밍 중에는 아래 형태의 이벤트가 반복 전송됩니다.

    ```
    event: chunk
    data: {"content": "창"}
    
    event: chunk
    data: {"content": "덕"}
    
    event: chunk
    data: {"content": "궁으로 변경했습니다!"}
    ```


#### **3.2 성공 - 스트리밍 완료**

- **Description**: 스트리밍이 완료되면 최종 메타데이터를 포함한 done 이벤트가 전송됩니다.
    - 일반 메시지일 때:

        ```
        event: done
        data: {
          "userMessage": {
            "messageId": "msg-041",
            "role": "user",
            "content": "경복궁 대신 창덕궁으로 바꿔줘",
            "createdAt": "2026-04-03T22:00:00"
          },
          "assistantMessage": {
            "messageId": "msg-042",
            "role": "assistant",
            "content": "네, 어떤 도움이 필요하신가요?",
            "createdAt": "2026-04-03T22:00:05"
          }
        }
        ```

    - itinerary 변경일 때:

        > AI 서버는 수정된 날짜만 전송하지만, 백엔드에서 기존 일정에 병합한 뒤 **전체 날짜**를 반환합니다.

        ```
        event: done
        data: {
          "userMessage": {
            "messageId": "msg-041",
            "role": "user",
            "content": "경복궁 대신 창덕궁으로 바꿔줘",
            "createdAt": "2026-04-03T22:00:00"
          },
          "assistantMessage": {
            "messageId": "msg-042",
            "role": "assistant",
            "content": "창덕궁으로 변경했습니다!",
            "createdAt": "2026-04-03T22:00:05"
          },
          "itinerary": {
            "itineraryId": "aaa-111",
            "destinations": [{"city": "서울", "start_date": "2026-05-01", "end_date": "2026-05-04"}],
            "startDate": "2026-05-01",
            "endDate": "2026-05-04",
            "dayPlans": {
              "2026-05-01": [
                {"index": 0, "plan_name": "창덕궁 방문", "time": "09:00 ~ 12:00", "place": "창덕궁", "note": "후원 투어 예약 필요", "cost": {"amount": 3000, "currency": "KRW", "amount_krw": null}, "image_url": "http://tong.visitkorea.or.kr/cms/resource/.../changdeokgung.jpg", "url": null, "status": "todo"},
                {"index": 1, "plan_name": "광장시장 점심", "time": "12:00 ~ 14:30", "place": "광장시장", "note": "", "cost": null, "image_url": null, "url": null, "status": "todo"}
              ],
              "2026-05-02": [
                {"index": 0, "plan_name": "N서울타워 방문", "time": "10:00 ~ 12:00", "place": "남산타워", "note": "", "cost": null, "image_url": "http://tong.visitkorea.or.kr/cms/resource/.../nseoultower.jpg", "url": null, "status": "todo"}
              ],
              "2026-05-03": [],
              "2026-05-04": []
            },
            "updatedAt": "2026-04-03T22:00:05"
          }
        }
        ```

    - change (일정 기본 정보 수정) 일 때:

        ```
        event: done
        data: {
          "userMessage": {
            "messageId": "msg-041",
            "role": "user",
            "content": "여행 날짜 5월 3일부터 7일로 바꿔줘",
            "createdAt": "2026-04-03T22:00:00"
          },
          "assistantMessage": {
            "messageId": "msg-042",
            "role": "assistant",
            "content": "여행 기간을 5월 3일~7일로 변경했습니다.",
            "createdAt": "2026-04-03T22:00:05"
          },
          "change": {
            "itineraryId": "aaa-111",
            "destinations": [{"city": "도쿄", "start_date": "2026-05-03", "end_date": "2026-05-07"}],
            "startDate": "2026-05-03",
            "endDate": "2026-05-07",
            "totalDays": 5,
            "budget": 500000.00,
            "adultCount": 2,
            "childCount": 1,
            "childAges": [5],
            "updatedAt": "2026-04-03T22:00:05"
          }
        }
        ```

    - reservation (예약) 일 때:

        > AI 서버는 `reservationId` 없이 `externalRefId`를 전송합니다. Spring Boot가 `reservations` 테이블에 저장하면서 UUID PK(`reservationId`)가 생성되며, done 이벤트에 포함하여 반환합니다.
        > `detail` 필드 구조는 `type`에 따라 다릅니다.

        **type: "flight" 일 때**

        ```
        event: done
        data: {
          "userMessage": {
            "messageId": "msg-041",
            "role": "user",
            "content": "항공권 예약해줘",
            "createdAt": "2026-04-03T22:00:00"
          },
          "assistantMessage": {
            "messageId": "msg-042",
            "role": "assistant",
            "content": "대한항공 KE123편을 예약했습니다.",
            "createdAt": "2026-04-03T22:00:05"
          },
          "reservation": {
            "reservationId": "c3a7db7a-3b93-4b50-a667-4ac922e2ff11",
            "type": "flight",
            "status": "confirmed",
            "bookingUrl": "https://booking.tripai.app/flights/KE123",
            "externalRefId": "KE-20260511-1A2B3C",
            "detail": {
              "airline": "대한항공",
              "flight_no": "KE123",
              "departure": {"airport": "ICN", "datetime": "2026-05-01T09:00:00"},
              "arrival": {"airport": "NRT", "datetime": "2026-05-01T11:30:00"},
              "seat_class": "economy",
              "passengers": [{"name": "홍길동", "passport": "M12345678"}]
            },
            "totalPrice": 320000.00,
            "currency": "KRW",
            "reservedAt": "2026-05-11T09:00:00+09:00"
          }
        }
        ```

        **type: "hotel" 일 때**

        ```
        event: done
        data: {
          "userMessage": {
            "messageId": "msg-041",
            "role": "user",
            "content": "호텔 예약해줘",
            "createdAt": "2026-04-03T22:00:00"
          },
          "assistantMessage": {
            "messageId": "msg-042",
            "role": "assistant",
            "content": "예시호텔(신주쿠)을 예약했습니다.",
            "createdAt": "2026-04-03T22:00:05"
          },
          "reservation": {
            "reservationId": "c3a7db7a-3b93-4b50-a667-4ac922e2ff11",
            "type": "hotel",
            "status": "confirmed",
            "bookingUrl": "https://booking.tripai.app/stays/HTL-20260511-1A2B3C",
            "externalRefId": "HTL-20260511-1A2B3C",
            "detail": {
              "name": "예시호텔(신주쿠)",
              "check_in": "2026-05-01",
              "check_out": "2026-05-03",
              "rooms": 1,
              "guests": 3
            },
            "totalPrice": 240000.00,
            "currency": "KRW",
            "reservedAt": "2026-05-11T09:00:00+09:00"
          }
        }
        ```

    - cancel (예약 취소) 일 때:

        ```
        event: done
        data: {
          "userMessage": {
            "messageId": "msg-041",
            "role": "user",
            "content": "항공권 예약 취소해줘",
            "createdAt": "2026-04-03T22:00:00"
          },
          "assistantMessage": {
            "messageId": "msg-042",
            "role": "assistant",
            "content": "KE123편 예약을 취소했습니다.",
            "createdAt": "2026-04-03T22:00:05"
          },
          "cancel": {
            "reservationId": "c3a7db7a-3b93-4b50-a667-4ac922e2ff11",
            "status": "cancelled",
            "cancelledAt": "2026-04-10T10:00:00+09:00"
          }
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


#### **3.4 권한 없음 (403 Forbidden)**

- **Description**: 해당 채팅방의 소유자가 아닌 경우입니다.

    ```json
    {
      "status": 403,
      "error": "Forbidden",
      "message": "You do not have permission to access this chat room."
    }
    ```


#### **3.5 리소스 없음 (404 Not Found)**

- **Description**: 해당 roomId에 해당하는 채팅방이 존재하지 않는 경우입니다.

    ```json
    {
      "status": 404,
      "error": "Not Found",
      "message": "Chat room not found."
    }
    ```


---

### **4. 비즈니스 로직 및 DB 스키마**

### **4.1 동작 흐름 (Sequence)**

1. **Token Parsing**: Authorization 헤더에서 JWT를 추출하고 검증합니다.
2. **Claim Extraction**: JWT의 `sub` 클레임을 추출하여 `clerk_id`로 사용합니다.
3. **Resource Check**: `roomId`로 chat_rooms 테이블을 조회합니다. 존재하지 않으면 404를 반환합니다.
4. **Authorization Check**: `chat_rooms.clerk_id`가 요청자의 `clerk_id`와 일치하는지 확인합니다. 일치하지 않으면 403을 반환합니다.
5. **FastAPI 호출**: WebClient로 FastAPI AI Agent에 사용자 메시지 및 `room_id` 전달, SSE 스트리밍 요청합니다.
6. **스트리밍 전달**: FastAPI로부터 수신한 토큰을 Flux로 프론트엔드에 실시간 전달합니다.
7. **Embedding 생성**: 스트리밍 완료 후 FastAPI에서 사용자 메시지 및 assistant 메시지 본문을 `google-generativeai`의 `embed_content`로 임베딩 벡터를 생성합니다.
8. **스트리밍 완료 후 분기 처리(java 서버에서 처리)**: FastAPI 응답의 `type`을 기준으로 DB 저장을 분기합니다. done 이벤트에 embedding 벡터를 포함하여 Java로 전달합니다. `userMessage` / `assistantMessage` 는 항상 저장합니다.
    - **Memory 갱신**: done 이벤트에 `memory` 필드가 non-null이면 `chat_rooms.ai_summary`와 `chat_rooms.preferences`를 갱신합니다. (type과 무관하게 처리)
    - `chat` → `chat_messages` 저장 + embedding 저장. 추가 도메인 처리 없이 종료합니다. (`assistant` 메시지의 `action_result`는 `null`)
    - `itinerary` → chat_messages 저장 + embedding 저장 + itineraries 수정 + itinerary_logs 스냅샷 저장 + `action_result`에 변경 후 일정 정보(`itineraryId`, `destinations`, `startDate`, `endDate`, `totalDays`, `dayPlans`) 저장
        - AI 서버는 수정된 날짜만 전송하며, 백엔드에서 기존 `day_plans`에 병합(수정되지 않은 날짜는 그대로 유지)합니다.
        - 저장 전 각 날짜의 아이템 배열을 `time` 오름차순으로 정렬하여 저장합니다.
    - `change` → chat_messages 저장 + embedding 저장 + itineraries 기본 정보 수정 + itinerary_logs 스냅샷 저장 + `action_result`에 변경 후 기본 정보(`itineraryId`, `destinations`, `startDate`, `endDate`, `totalDays`, `budget`, `adultCount`, `childCount`, `childAges`) 저장. 사용자가 여행지, 날짜, 예산, 인원 등 기본 정보 수정을 AI에게 요청할 경우 처리합니다.
    - `reservation` → chat_messages 저장 + embedding 저장 + reservations 신규 저장 (`externalRefId` 기준, UUID PK 자동 생성, status = `confirmed`) + `action_result`에 예약 정보(`reservationId`, `type`, `status`, `bookingUrl`, `externalRefId`, `detail`, `totalPrice`, `currency`, `reservedAt`) 저장
    - `cancel` → chat_messages 저장 + embedding 저장 + reservations 취소 처리 (`reservationId`로 조회 후 status = `cancelled`, cancelledAt 갱신) + `action_result`에 취소 정보(`reservationId`, `status`, `cancelledAt`) 저장
9. **done 이벤트 전송**: 저장 완료 후 최종 메타데이터를 done 이벤트로 프론트엔드에 전송합니다. `itinerary` 타입의 경우 병합 후 전체 날짜의 `dayPlans`를 반환합니다. 아이템 배열은 `time` 오름차순 정렬 후 `index`(0부터)를 부여하여 반환합니다.

### **4.2 DB 저장 구조**

***chat_messages Table***

| Column | Type | Description |
| --- | --- | --- |
| `room_id` | UUID | 소속 채팅방 ID |
| `role` | VARCHAR(20) | `user` / `assistant` |
| `content` | TEXT | 메시지 본문 |
| `embedding` | vector(1536) | 메시지 임베딩 벡터 |
| `created_at` | TIMESTAMP | 전송 일시 |

---

### **5. 호출 예시 (Example)**

```bash
curl -X POST https://your-api-domain.com/api/v1/chat-messages/{roomId} \
  -H "Authorization: Bearer <clerk_jwt_token>" \
  -H "Content-Type: application/json" \
  -d '{"content": "경복궁 대신 창덕궁으로 바꿔줘"}'
```