package com.uua.ingest;

import com.uua.embedding.EmbeddingException;

/**
 * Ingest 중간 청크에서 임베딩이 실패했을 때 던지는 예외.
 * 이미 저장된 청크는 보존(부분 커밋) — v1엔 재시도 큐가 없어 큰 ingest를 통째로 롤백하면 영구 실패가 되기 때문.
 *
 * GlobalExceptionHandler가 503 + {error: embedding_unavailable, cause, committed}로 매핑.
 */
public class IngestPartialFailure extends RuntimeException {

    private final EmbeddingException.Reason reason;
    private final int committed;
    private final int failedAt; // 1-based chunk index

    public IngestPartialFailure(EmbeddingException cause, int committed, int failedAt) {
        super("ingest partial failure committed=" + committed + " failedAt=" + failedAt, cause);
        this.reason = cause.reason();
        this.committed = committed;
        this.failedAt = failedAt;
    }

    public EmbeddingException.Reason reason() { return reason; }
    public int committed() { return committed; }
    public int failedAt() { return failedAt; }
}
