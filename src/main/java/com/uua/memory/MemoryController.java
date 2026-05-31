package com.uua.memory;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;

/**
 * 단계 ②의 단일 엔드포인트. 검증·에러 매핑은 GlobalExceptionHandler가 담당.
 *
 * 단계 ③(읽기 API)에서 같은 컨트롤러에 GET/POST를 추가할 예정.
 */
@RestController
@RequestMapping("/api/memories")
public class MemoryController {

    private final MemoryService memoryService;

    public MemoryController(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @PostMapping
    public ResponseEntity<CreateMemoryResponse> create(@Valid @RequestBody CreateMemoryRequest req) {
        CreateMemoryResponse body = memoryService.create(req);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(body.id())
                .toUri();
        return ResponseEntity.created(location).body(body);
    }
}
