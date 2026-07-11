-- V14__add_origin_to_itineraries.sql
-- 설명: itineraries.origin(JSONB) 컬럼 추가 — 채팅방 생성 시 입력받는 출발지 정보 저장
--       destinations와 동일한 패턴: {"city": "..."}

ALTER TABLE itineraries ADD COLUMN IF NOT EXISTS origin JSONB NOT NULL DEFAULT '{}';
