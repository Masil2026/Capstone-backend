-- V13__fix_cascade_delete_for_user_withdrawal.sql
-- 설명: 회원탈퇴 시 reservations cascade 삭제 누락 수정
--   reservations.itinerary_id: ON DELETE RESTRICT → ON DELETE CASCADE

ALTER TABLE reservations
    DROP CONSTRAINT reservations_itinerary_id_fkey;

ALTER TABLE reservations
    ADD CONSTRAINT reservations_itinerary_id_fkey
        FOREIGN KEY (itinerary_id) REFERENCES itineraries (id) ON DELETE CASCADE;
