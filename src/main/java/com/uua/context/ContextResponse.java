package com.uua.context;

import java.time.Instant;
import java.util.List;

/**
 * POST /api/context 응답 바디. DESIGN.md의 v1 응답 예시 형식을 그대로 따른다.
 *
 * pack: 인용 번호가 붙은 컨텍스트 묶음 문자열 — LLM 프롬프트에 바로 붙여 쓸 수 있게.
 * items: 선택된 청크들의 메타데이터(점수/출처 포함) — 디버깅·평가셋용.
 * usedTokens: 선택된 청크들의 token_count 합. maxTokens 이하 보장.
 * 결과 0건이면 pack=""(빈 문자열), items=[], usedTokens=0.
 */
public record ContextResponse(
        String pack,
        List<ContextItem> items,
        int usedTokens,
        int maxTokens
) {
    /**
     * 한 행. source는 "memory:{id}" 형식.
     * score는 finalScore = cosineSim + 0.1·exp(-ageDays/30).
     */
    public record ContextItem(
            String text,
            String source,
            Instant createdAt,
            double score
    ) {}
}
