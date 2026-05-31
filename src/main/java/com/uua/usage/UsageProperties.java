package com.uua.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * usage.* 설정.
 *
 * embeddingEnabled: 킬스위치. false면 외부 호출 전 차단 → EmbeddingException(KILLED) → 503.
 *                   재시작 토글(런타임 hot-swap 안 함, v1엔 충분).
 * dailyCap: SUCCESS 카운트가 이 값에 도달하면 EmbeddingException(DAILY_LIMIT) → 503.
 *           Gemini 무료티어 RPD(~1500)보다 여유 두고 1000 기본.
 */
@ConfigurationProperties(prefix = "usage")
public record UsageProperties(
        boolean embeddingEnabled,
        int dailyCap
) {
}
