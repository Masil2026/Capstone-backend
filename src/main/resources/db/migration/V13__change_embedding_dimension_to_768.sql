-- V13__change_embedding_dimension_to_768.sql
-- 설명: 임베딩 모델 교체(OpenAI 1536차원 → Google text-embedding-004 768차원)에 따라 기존 embedding NULL 초기화 및 컬럼 타입 변경

DROP INDEX IF EXISTS idx_chat_messages_embedding;

UPDATE chat_messages SET embedding = NULL;

ALTER TABLE chat_messages
    ALTER COLUMN embedding TYPE vector(768);

CREATE INDEX idx_chat_messages_embedding ON chat_messages USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
