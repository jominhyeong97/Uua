package com.uua.usage;

import java.util.Map;

/**
 * GET /api/usage/summary 응답.
 * "비용 0" 숫자 증명용 — today + last7Days 합산 + 현재 한도/킬스위치 상태.
 */
public record UsageSummary(
        Today today,
        LastWindow last7Days,
        Limits limits
) {
    public record Today(
            String date,           // ISO yyyy-MM-dd (UTC)
            long embedCalls,       // 모든 outcome 합
            long successes,
            long failures,
            long tokensTotal,      // SUCCESS만 합산
            Map<String, Long> byOutcome
    ) {}

    public record LastWindow(
            long embedCalls,
            long tokensTotal
    ) {}

    public record Limits(
            int dailyCap,
            long remainingToday,   // dailyCap - todaySuccesses (음수면 0)
            boolean killSwitchEnabled  // true면 차단된 상태(embeddingEnabled=false)
    ) {}
}
