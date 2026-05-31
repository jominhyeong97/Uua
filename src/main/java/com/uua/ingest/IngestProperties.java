package com.uua.ingest;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ingest.* 설정.
 *
 * throttleMillis: 청크별 임베딩 호출 사이 딜레이(ms). 무료 RPM 보호용. 기본 200ms.
 * windowChars: 청크 한 개의 글자 수 상한. 기본 2000(≈ 500 토큰, DESIGN.md SSOT).
 */
@ConfigurationProperties(prefix = "ingest")
public record IngestProperties(
        long throttleMillis,
        int windowChars
) {
}
