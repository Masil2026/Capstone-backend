package com.mju.capstone_backend.global.dev;

import com.mju.capstone_backend.domain.reservation.entity.Reservation;
import com.mju.capstone_backend.domain.reservation.repository.ReservationRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 개발용 예약 데이터 시드 엔드포인트.
 *
 * GET /api/v1/reservations 테스트를 위해 항공·숙소·렌트카 샘플 예약 3건을 생성한다.
 * itineraryId는 기존 일정의 UUID를 사용한다 (chat-rooms → itinerary 생성 후 획득).
 *
 * Swagger 사용법:
 * 1. POST /dev/reservations/seed — itineraryId 전달 → 샘플 예약 3건 생성
 * 2. GET /api/v1/reservations — dev-{clerkId} 토큰으로 조회 확인
 * 3. DELETE /dev/reservations/seed/{itineraryId} — 시드 데이터 정리
 *
 * @Profile("dev") — 운영 환경에서는 빈 자체가 등록되지 않음.
 */
@Tag(name = "[DEV] Reservation API")
@RestController
@RequestMapping("/dev/reservations")
@Profile("dev")
@RequiredArgsConstructor
public class DevReservationController {

        private static final String FLIGHT_DETAIL = """
                        {
                          "airline": "대한항공",
                          "flight_no": "KE123",
                          "departure": {"airport": "ICN", "datetime": "2026-05-01T09:00:00"},
                          "arrival":   {"airport": "NRT", "datetime": "2026-05-01T11:30:00"},
                          "seat_class": "economy",
                          "passengers": [{"name": "홍길동", "passport": "M12345678"}]
                        }
                        """;

        private static final String ACCOMMODATION_DETAIL = """
                        {
                          "hotel_name": "롯데호텔 도쿄",
                          "room_type": "디럭스 더블",
                          "check_in": "2026-05-01",
                          "check_out": "2026-05-03",
                          "guests": 2
                        }
                        """;

        private static final String ACCOMMODATION_DETAIL_2 = """
                        {
                          "hotel_name": "신주쿠 그랜드 호텔",
                          "room_type": "스탠다드 트윈",
                          "check_in": "2026-05-03",
                          "check_out": "2026-05-05",
                          "guests": 2
                        }
                        """;

        @Schema(example = """
                        {
                          "itineraryId": "550e8400-e29b-41d4-a716-446655440000"
                        }
                        """)
        record SeedRequest(UUID itineraryId) {
        }

        private final ReservationRepository reservationRepository;

        @Operation(summary = "[DEV] 테스트 예약 데이터 생성", description = "itineraryId에 항공·숙소 샘플 예약 3건을 생성하고 생성된 ID 목록을 반환합니다.")
        @PostMapping("/seed")
        @ResponseStatus(HttpStatus.CREATED)
        public Mono<Map<String, Object>> seed(@RequestBody SeedRequest request) {
                OffsetDateTime now = OffsetDateTime.now();
                List<Reservation> samples = List.of(
                                Reservation.of(request.itineraryId(), "flight", "confirmed", "ai",
                                                null, "KE-20260501-001", FLIGHT_DETAIL,
                                                new BigDecimal("350000"), "KRW", now),
                                Reservation.of(request.itineraryId(), "accommodation", "confirmed", "user",
                                                null, "LOTTE-20260501", ACCOMMODATION_DETAIL,
                                                new BigDecimal("180000"), "KRW", now),
                                Reservation.of(request.itineraryId(), "accommodation", "confirmed", "user",
                                                null, "SHINJUKU-20260503", ACCOMMODATION_DETAIL_2,
                                                new BigDecimal("120000"), "KRW", now));
                return reservationRepository.saveAll(samples)
                                .map(Reservation::getId)
                                .collectList()
                                .map(ids -> Map.of("itineraryId", request.itineraryId(), "reservationIds", ids));
        }

        @Operation(summary = "[DEV] 테스트 예약 데이터 삭제", description = "itineraryId에 연결된 모든 예약을 삭제합니다.")
        @DeleteMapping("/seed/{itineraryId}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public Mono<Void> deleteSeed(@PathVariable UUID itineraryId) {
                return reservationRepository.deleteAllByItineraryId(itineraryId);
        }
}
