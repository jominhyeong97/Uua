package com.uua.context;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/context 요청 바디.
 *
 * 검증 규칙(단계③ PRD):
 * - query: 비어있지 않음 + 8000자 이하 (위반시 400, 길이만 위반시 413)
 * - projectKey: 비어있지 않음 + 255자 이하 — 다른 프로젝트 메모리가 섞이지 않도록 SQL WHERE에 그대로 들어감
 * - maxTokens: 컨텍스트 팩 토큰 예산 (1..8000), 기본 1000
 * - topK: 의미검색 후보 수 (1..100), 기본 20
 *
 * maxTokens/topK는 null 허용 — 호출자가 안 보내면 서비스에서 기본값 적용.
 */
public record ContextRequest(
        @NotBlank
        @Size(max = 8000)
        String query,

        @NotBlank
        @Size(max = 255)
        String projectKey,

        @Min(1)
        @Max(8000)
        Integer maxTokens,

        @Min(1)
        @Max(100)
        Integer topK
) {
    public static final int DEFAULT_MAX_TOKENS = 1000;
    public static final int DEFAULT_TOP_K = 20;

    public int maxTokensOrDefault() {
        return maxTokens != null ? maxTokens : DEFAULT_MAX_TOKENS;
    }

    public int topKOrDefault() {
        return topK != null ? topK : DEFAULT_TOP_K;
    }
}
