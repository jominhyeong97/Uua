package com.uua.usage;

import com.uua.embedding.EmbeddingException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 일일 상한 체크. 외부 임베딩 호출 *전*에 호출되어 한도 초과 시 즉시 503으로 차단한다.
 *
 * "오늘"의 기준은 서버 UTC 자정 — KST와 9시간 차이가 있지만 v1엔 단순화. v2에서 사용자 타임존 도입.
 *
 * 카운트는 SUCCESS만 — 실패 호출은 토큰 비용이 들지 않으므로 한도 계산에서 제외.
 */
@Component
public class UsageLimiter {

    private final UsageJdbcRepository repo;
    private final UsageProperties props;
    private final Clock clock;

    public UsageLimiter(UsageJdbcRepository repo, UsageProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    /**
     * 일일 SUCCESS 카운트가 한도에 도달했으면 {@link EmbeddingException}(DAILY_LIMIT)을 던진다.
     * 정상이면 부수효과 없음.
     */
    public void check() {
        Instant todayStart = Instant.now(clock)
                .atOffset(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay()
                .toInstant(ZoneOffset.UTC);
        long used = repo.countSuccessSince(todayStart);
        if (used >= props.dailyCap()) {
            throw new EmbeddingException(EmbeddingException.Reason.DAILY_LIMIT,
                    "daily embedding cap reached: used=" + used + " cap=" + props.dailyCap());
        }
    }
}
