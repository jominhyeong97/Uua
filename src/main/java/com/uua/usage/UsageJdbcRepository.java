package com.uua.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * usage_log 테이블 전용 리포지터리.
 * insert는 UsageRecorder가 REQUIRES_NEW 트랜잭션으로 호출,
 * count/tokens 집계는 UsageLimiter / UsageQueryService가 호출.
 */
@Repository
public class UsageJdbcRepository {

    private static final String INSERT_SQL = """
            INSERT INTO usage_log (op, model, token_count, latency_ms, outcome)
            VALUES (?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public UsageJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String op, String model, int tokenCount, int latencyMs, String outcome) {
        jdbc.update(INSERT_SQL, op, model, tokenCount, latencyMs, outcome);
    }

    /** since(포함) 이후 outcome=SUCCESS인 행 개수. 일일 상한 체크용. */
    public long countSuccessSince(Instant since) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM usage_log WHERE created_at >= ? AND outcome = 'SUCCESS'",
                Long.class,
                Timestamp.from(since)
        );
        return n == null ? 0L : n;
    }

    /** since(포함) 이후 전체 행 개수(성공+실패). summary용. */
    public long countSince(Instant since) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM usage_log WHERE created_at >= ?",
                Long.class,
                Timestamp.from(since)
        );
        return n == null ? 0L : n;
    }

    /** since 이후 SUCCESS 행의 token_count 합. 실패는 토큰 비용으로 보지 않음. */
    public long tokensSuccessSince(Instant since) {
        Long n = jdbc.queryForObject(
                "SELECT COALESCE(SUM(token_count), 0) FROM usage_log "
                        + "WHERE created_at >= ? AND outcome = 'SUCCESS'",
                Long.class,
                Timestamp.from(since)
        );
        return n == null ? 0L : n;
    }

    /** since 이후 outcome별 카운트 Map. 0건 outcome은 결과에 없음. */
    public Map<String, Long> countByOutcomeSince(Instant since) {
        Map<String, Long> out = new HashMap<>();
        jdbc.query(
                "SELECT outcome, count(*) AS n FROM usage_log "
                        + "WHERE created_at >= ? GROUP BY outcome",
                rs -> {
                    out.put(rs.getString("outcome"), rs.getLong("n"));
                },
                Timestamp.from(since)
        );
        return out;
    }
}
