package com.uua.ingest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 단계 ④ 자동 핸드오프 인입 API.
 * 검증/에러 매핑은 GlobalExceptionHandler가 담당.
 *
 * sessionId(경로변수)는 메모리 행의 session_key 컬럼(VARCHAR(255))에 그대로 들어가므로 @Size로 보호.
 */
@RestController
@Validated
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping("/api/sessions/{sessionId}/ingest")
    public ResponseEntity<IngestResponse> ingest(
            @PathVariable @NotBlank @Size(max = 255) String sessionId,
            @Valid @RequestBody IngestRequest req
    ) {
        IngestResponse body = ingestService.ingest(sessionId, req);
        return ResponseEntity.status(201).body(body);
    }
}
