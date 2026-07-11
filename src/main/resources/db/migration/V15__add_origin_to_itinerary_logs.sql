-- V15__add_origin_to_itinerary_logs.sql
-- 설명: itinerary_logs.origin(JSONB) 컬럼 추가 — PATCH /itineraries/{id}에서 origin 수정 시
--       변경 전 값을 스냅샷으로 저장. 기존 로그 행은 origin 이력이 없으므로 nullable

ALTER TABLE itinerary_logs ADD COLUMN IF NOT EXISTS origin JSONB;
