package com.uua.context;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 단계 ③ 읽기 API. 검증·에러 매핑은 GlobalExceptionHandler가 담당.
 *
 * 단계 ②와 달리 결과 0건도 200으로 응답(빈 pack/items) — "검색 결과 없음"은 정상 상태.
 */
@RestController
@RequestMapping("/api/context")
public class ContextController {

    private final ContextService contextService;

    public ContextController(ContextService contextService) {
        this.contextService = contextService;
    }

    @PostMapping
    public ContextResponse buildPack(@Valid @RequestBody ContextRequest req) {
        return contextService.buildPack(req);
    }
}
