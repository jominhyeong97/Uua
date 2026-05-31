package com.uua.common;

import com.uua.embedding.EmbeddingException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 모든 컨트롤러의 예외 → JSON 응답 매핑.
 *
 * 매핑(PRD §4 상태코드 표):
 * - Bean Validation 실패(@NotBlank 등) → 400 (field, message)
 * - 긴 본문(text/query) 길이 초과(@Size(max=8000)) → 413 (text_too_long)
 * - {@link EmbeddingException} → 503 (embedding_unavailable + cause enum)
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final int TEXT_MAX = 8000;
    // 단계 ②의 text(POST /api/memories) + 단계 ③의 query(POST /api/context)는
    // 모두 Size(max=8000)만 걸려있어 Size 위반 = 길이 초과로 단언 가능.
    private static final Set<String> LONG_BODY_FIELDS = Set.of("text", "query");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors();

        boolean textTooLong = errors.stream().anyMatch(e ->
                LONG_BODY_FIELDS.contains(e.getField()) && "Size".equals(e.getCode()));
        if (textTooLong) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(Map.of(
                            "error", "text_too_long",
                            "max", TEXT_MAX
                    ));
        }

        FieldError first = errors.isEmpty() ? null : errors.get(0);
        return ResponseEntity.badRequest().body(Map.of(
                "error", "validation_failed",
                "field", first != null ? first.getField() : "",
                "message", first != null && first.getDefaultMessage() != null ? first.getDefaultMessage() : ""
        ));
    }

    @ExceptionHandler(EmbeddingException.class)
    public ResponseEntity<Map<String, Object>> handleEmbedding(EmbeddingException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "embedding_unavailable",
                        "cause", ex.reason().name()
                ));
    }
}
