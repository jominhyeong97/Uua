package com.uua.usage;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 단계 ⑤ 사용량 요약 엔드포인트.
 * 성공기준 ⓑ("비용 0" 숫자 증명)의 표면. 정적 HTML 표 없이 JSON만(설계 SSOT).
 */
@RestController
@RequestMapping("/api/usage")
public class UsageController {

    private final UsageQueryService queryService;

    public UsageController(UsageQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/summary")
    public UsageSummary summary() {
        return queryService.summary();
    }
}
