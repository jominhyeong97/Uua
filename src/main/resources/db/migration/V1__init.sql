-- Uua 초기 스키마 (단계 ①)
-- pgvector 확장 + memory_item 테이블. JPA 엔티티는 단계 ②에서 추가한다.

-- 벡터 타입/연산자(<=>)를 쓰기 위한 확장. pgvector/pgvector 이미지에는 포함되어 있다.
CREATE EXTENSION IF NOT EXISTS vector;

-- 저장 단위 1개 = 의미검색 가능한 텍스트 청크 + 그 임베딩.
-- 임베딩은 Gemini text-embedding-004(768차원) 고정 → vector(768).
CREATE TABLE memory_item (
    id           BIGSERIAL    PRIMARY KEY,
    project_key  VARCHAR(255) NOT NULL,
    session_key  VARCHAR(255),
    text         TEXT         NOT NULL,
    embedding    vector(768)  NOT NULL,
    source       VARCHAR(64)  NOT NULL,
    model        VARCHAR(128) NOT NULL,
    token_count  INTEGER      NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- v1 미사용: Approach B(버전 체인) 대비 자리만 잡아둠.
    supersedes_id BIGINT      REFERENCES memory_item(id)
);

-- 프로젝트 단위로 자주 조회하므로 보조 인덱스.
-- 벡터 ANN 인덱스(IVFFlat/HNSW)는 데이터가 적은 v1에서는 생략(전수검색). 늘면 추가.
CREATE INDEX idx_memory_item_project_key ON memory_item (project_key);
