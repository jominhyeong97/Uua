-- 단계 ⑤ 비용/남용 방어: 임베딩 호출별 사용량 로그.
--
-- 한 행 = 임베딩 1콜. 성공/실패 모두 기록.
-- 일일 상한 체크, /api/usage/summary 응답의 SSOT.
--
-- v1엔 op='embed'만. 다른 외부모델 호출이 생기면 op로 구분.
-- endpoint(어느 HTTP 경로에서 발생했는지) 컬럼은 v1 미포함 — 호출자 정보를 임베딩 클라이언트까지
-- 끌고 내려가는 비용보다 가치가 작음.
CREATE TABLE usage_log (
    id           BIGSERIAL    PRIMARY KEY,
    op           VARCHAR(32)  NOT NULL,    -- 'embed'
    model        VARCHAR(128) NOT NULL,
    token_count  INTEGER      NOT NULL,    -- 입력 토큰 근사(chars/4). 실패 콜은 호출 시점 추정값.
    latency_ms   INTEGER      NOT NULL,
    outcome      VARCHAR(32)  NOT NULL,    -- 'SUCCESS' | 'RATE_LIMIT' | 'TIMEOUT' | 'SERVER_ERROR' | 'INVALID_RESPONSE' | 'API_KEY_MISSING' | 'KILLED' | 'DAILY_LIMIT'
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- 일일 상한 카운트 + 시간 윈도우 합산에 자주 쓰임.
CREATE INDEX idx_usage_log_created_at ON usage_log (created_at);
-- outcome별 카운트(SUCCESS만 상한에 포함, summary 분해용).
CREATE INDEX idx_usage_log_outcome ON usage_log (outcome);
