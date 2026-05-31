package com.uua.usage;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/usage/summary가 UsageSummary 구조를 그대로 직렬화하는지.
 */
@WebMvcTest(UsageController.class)
class UsageControllerTest {

    @Autowired MockMvc mvc;

    @MockitoBean UsageQueryService queryService;

    @Test
    void GET_summary_정상_200_구조_검증() throws Exception {
        UsageSummary fake = new UsageSummary(
                new UsageSummary.Today(
                        "2026-05-31",
                        43, 40, 3, 12_000,
                        Map.of("SUCCESS", 40L, "RATE_LIMIT", 2L, "TIMEOUT", 1L)
                ),
                new UsageSummary.LastWindow(300, 80_000),
                new UsageSummary.Limits(1000, 960, false)
        );
        given(queryService.summary()).willReturn(fake);

        mvc.perform(get("/api/usage/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today.date").value("2026-05-31"))
                .andExpect(jsonPath("$.today.embedCalls").value(43))
                .andExpect(jsonPath("$.today.successes").value(40))
                .andExpect(jsonPath("$.today.tokensTotal").value(12000))
                .andExpect(jsonPath("$.today.byOutcome.SUCCESS").value(40))
                .andExpect(jsonPath("$.last7Days.embedCalls").value(300))
                .andExpect(jsonPath("$.limits.dailyCap").value(1000))
                .andExpect(jsonPath("$.limits.remainingToday").value(960))
                .andExpect(jsonPath("$.limits.killSwitchEnabled").value(false));
    }
}
