package com.uua.common;

import com.uua.embedding.EmbeddingException;
import com.uua.ingest.IngestPartialFailure;
import jakarta.validation.ConstraintViolationException;
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
    // Size(max=8000)을 걸어 위반 시 "본문이 한도를 넘었다" → 413으로 매핑.
    // 단계 ④의 text(POST /api/sessions/{id}/ingest)는 Size(max=200000)으로 의미가 다름
    // (200KB 덤프 자체가 너무 큼 = 400 validation_failed). 그래서 필드명만으로 안 묶고,
    // 같은 필드라도 Size의 max 속성이 정확히 TEXT_MAX(=8000)일 때만 413으로 분기한다.
    private static final Set<String> LONG_BODY_FIELDS = Set.of("text", "query");

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldError> errors = ex.getBindingResult().getFieldErrors();

        boolean textTooLong = errors.stream().anyMatch(e ->
                LONG_BODY_FIELDS.contains(e.getField())
                        && "Size".equals(e.getCode())
                        && hasSizeMax(e, TEXT_MAX));
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

    /**
     * 단계 ④ ingest 중간 실패. 이미 커밋된 청크 개수를 함께 돌려준다 — 클라이언트가 "어디까지 들어갔는지" 알 수 있게.
     */
    @ExceptionHandler(IngestPartialFailure.class)
    public ResponseEntity<Map<String, Object>> handleIngestPartial(IngestPartialFailure ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "embedding_unavailable",
                        "cause", ex.reason().name(),
                        "committed", ex.committed(),
                        "failedAt", ex.failedAt()
                ));
    }

    /**
     * @Validated + @PathVariable/@RequestParam의 @Size/@NotBlank 위반은 ConstraintViolationException으로 던져진다.
     * MethodArgumentNotValidException과 달리 field가 "method.param" 형식이라 마지막 토큰만 추출.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraint(ConstraintViolationException ex) {
        String path = ex.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath().toString())
                .orElse("");
        int dot = path.lastIndexOf('.');
        String field = dot >= 0 ? path.substring(dot + 1) : path;
        return ResponseEntity.badRequest().body(Map.of(
                "error", "validation_failed",
                "field", field
        ));
    }

    /**
     * Spring의 SpringValidatorAdapter는 @Size 위반을 FieldError로 변환할 때 인자 배열에
     * (필드명 리졸버블) + 정렬된 어노테이션 속성(max, min)을 담는다. 순서에 의존하지 않고
     * "Integer가 expected 값과 같은 게 하나라도 있는지" 검사 — Size에서 max/min 둘 다 Integer.
     */
    private static boolean hasSizeMax(FieldError e, int expected) {
        Object[] args = e.getArguments();
        if (args == null) return false;
        for (Object a : args) {
            if (a instanceof Integer i && i == expected) return true;
        }
        return false;
    }
}
