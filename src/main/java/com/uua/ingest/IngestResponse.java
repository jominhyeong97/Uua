package com.uua.ingest;

import java.util.List;

/**
 * POST /api/sessions/{sessionId}/ingest 응답 (201 Created).
 *
 * ids는 저장 순서대로(=청크 순서). tokensTotal은 모든 청크의 token_count 합.
 */
public record IngestResponse(
        String sessionId,
        String projectKey,
        int chunks,
        List<Long> ids,
        int tokensTotal
) {
}
