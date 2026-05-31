package com.uua.ingest;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/sessions/{sessionId}/ingest 요청 바디.
 *
 * text 상한 200KB — 한 ingest = 한 세션 덤프 1개분.
 * 2000자/청크 기준 최대 ~100 청크 → 200ms 스로틀에서 ~20초 처리(무료 RPM 안 깨짐).
 */
public record IngestRequest(
        @NotBlank
        @Size(max = 200_000)
        String text,

        @NotBlank
        @Size(max = 255)
        String projectKey
) {
}
