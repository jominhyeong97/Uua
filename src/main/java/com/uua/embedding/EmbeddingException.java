package com.uua.embedding;

/**
 * 임베딩 호출에서 발생한 모든 실패의 단일 예외.
 * 원인은 {@link Reason} enum으로 분류 — GlobalExceptionHandler가 503 응답 body의 cause 필드에 사용한다.
 *
 * v1엔 재시도 큐가 없다 — 이 예외가 나오면 호출은 그대로 503으로 끝난다(행 저장 안 됨, 트랜잭션 롤백).
 */
public class EmbeddingException extends RuntimeException {

    public enum Reason {
        /** application.properties의 gemini.api-key가 비어있음(env 미설정 포함). */
        API_KEY_MISSING,
        /** 외부 모델이 429(too many requests) 반환 — 무료 RPM 초과 등. */
        RATE_LIMIT,
        /** 외부 모델 5xx — 일시 장애. */
        SERVER_ERROR,
        /** connect/read timeout. */
        TIMEOUT,
        /** 응답은 200이지만 임베딩 길이가 설정한 dimension과 다르거나 형식이 어긋남. */
        INVALID_RESPONSE,
        /** 단계 ⑤ 킬스위치(usage.embedding-enabled=false) — 외부 호출이 아예 발생하지 않음. */
        KILLED,
        /** 단계 ⑤ 일일 상한(usage.daily-cap) 도달 — 외부 호출이 아예 발생하지 않음. */
        DAILY_LIMIT,
    }

    private final Reason reason;

    public EmbeddingException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public EmbeddingException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
