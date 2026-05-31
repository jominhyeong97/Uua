package com.uua.embedding;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gemini.* 설정 묶음 (application.properties).
 * 모든 값은 env 폴백을 가짐 — api-key만 빈 값 허용(테스트/로컬 부팅을 막지 않기 위함).
 * 실제 호출 시 api-key 빈 값이면 EmbeddingClient 구현체가 EmbeddingException을 던져 503으로 매핑된다.
 *
 * ⚠️ 실제 사용 모델은 {@code gemini-embedding-001}. V1__init.sql 주석엔 옛 이름
 * {@code text-embedding-004}가 적혀 있지만 적용된 마이그레이션은 체크섬 때문에 못 고친다
 * (수정 시 Flyway validation 실패). 차원은 768로 동일하므로 스키마 영향 없음.
 */
@ConfigurationProperties(prefix = "gemini")
public record EmbeddingProperties(
        String apiKey,
        String baseUrl,
        String model,
        int timeoutSeconds,
        int dimension
) {
}
