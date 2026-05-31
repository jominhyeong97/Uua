package com.uua.memory;

import java.time.Instant;

/**
 * POST /api/memories 응답 바디 (201 Created).
 * embedding/source/model은 노출하지 않는다 — 호출자가 다음 호출(읽기 API)을 만들 때 필요한 최소 키만.
 */
public record CreateMemoryResponse(
        long id,
        String projectKey,
        Instant createdAt,
        int tokenCount
) {
}
