package com.uua.usage;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;

/**
 * GET /api/usage/summary 응답 조립.
 * 모든 시각 계산은 주입된 Clock 기반 — 테스트에서 Clock.fixed로 결정성 확보.
 */
@Service
public class UsageQueryService {

    private final UsageJdbcRepository repo;
    private final UsageProperties props;
    private final Clock clock;

    public UsageQueryService(UsageJdbcRepository repo, UsageProperties props, Clock clock) {
        this.repo = repo;
        this.props = props;
        this.clock = clock;
    }

    public UsageSummary summary() {
        LocalDate today = LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC);
        Instant todayStart = today.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant sevenDaysAgo = todayStart.minusSeconds(6L * 86_400); // 오늘 + 직전 6일 = 7일

        Map<String, Long> byOutcome = repo.countByOutcomeSince(todayStart);
        long todayCalls = byOutcome.values().stream().mapToLong(Long::longValue).sum();
        long successes = byOutcome.getOrDefault("SUCCESS", 0L);
        long failures = todayCalls - successes;
        long todayTokens = repo.tokensSuccessSince(todayStart);

        long weekCalls = repo.countSince(sevenDaysAgo);
        long weekTokens = repo.tokensSuccessSince(sevenDaysAgo);

        long remaining = Math.max(0, props.dailyCap() - successes);

        return new UsageSummary(
                new UsageSummary.Today(
                        today.toString(),
                        todayCalls,
                        successes,
                        failures,
                        todayTokens,
                        byOutcome
                ),
                new UsageSummary.LastWindow(weekCalls, weekTokens),
                new UsageSummary.Limits(
                        props.dailyCap(),
                        remaining,
                        !props.embeddingEnabled()
                )
        );
    }
}
