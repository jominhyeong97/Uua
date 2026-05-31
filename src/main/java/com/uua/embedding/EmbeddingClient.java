package com.uua.embedding;

/**
 * 텍스트를 임베딩(의미를 표현하는 숫자 벡터)으로 변환하는 추상화.
 * 구현체는 외부 모델(현재 Gemini)에 의존하지만, 단계 ②에선 한 종류만 존재.
 *
 * 실패 시 항상 {@link EmbeddingException}을 던진다 — 호출자(MemoryService)는 트랜잭션 안에서
 * 예외를 그대로 전파하고, 컨트롤러 위 GlobalExceptionHandler가 503으로 매핑한다.
 */
public interface EmbeddingClient {

    /**
     * @param text 비어있지 않은 입력 텍스트(검증은 컨트롤러 단의 Bean Validation이 책임짐)
     * @return {@code EmbeddingProperties#dimension} 길이의 벡터
     * @throws EmbeddingException 외부 호출 실패·차원 불일치·api-key 미설정 등 모든 실패 경로
     */
    float[] embed(String text);
}
