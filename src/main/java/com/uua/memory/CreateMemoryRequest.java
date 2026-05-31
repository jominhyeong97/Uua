package com.uua.memory;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /api/memories 요청 바디.
 *
 * 검증 규칙(PRD §4):
 * - text: 비어있지 않음 + 8000자 이하 (위반시 400, 길이만 위반시 413)
 * - projectKey: 비어있지 않음 + 255자 이하 (위반시 400)
 * - sessionKey: nullable, 있다면 255자 이하
 *
 * source/model/tokenCount는 서버가 채운다(D2 결정) — 클라이언트가 보내지 않는다.
 */
public record CreateMemoryRequest(
        @NotBlank
        @Size(max = 8000)
        String text,

        @NotBlank
        @Size(max = 255)
        String projectKey,

        @Size(max = 255)
        String sessionKey
) {
}
