package com.uua.usage;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * UsageQueryService 단위 테스트 — Clock.fixed로 "오늘"을 결정성 있게 만든다.
 */
class UsageQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-05-31T10:00:00Z");

    private final UsageJdbcRepository repo = mock(UsageJdbcRepository.class);
    private final UsageProperties props = new UsageProperties(true, 1000);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UsageQueryService service = new UsageQueryService(repo, props, clock);

    @Test
    void summary는_오늘_outcome_분해와_주간_합산_한도를_담는다() {
        given(repo.countByOutcomeSince(any(Instant.class))).willReturn(
                Map.of("SUCCESS", 40L, "RATE_LIMIT", 2L, "TIMEOUT", 1L)
        );
        given(repo.tokensSuccessSince(any(Instant.class))).willReturn(12_000L, 80_000L);
        given(repo.countSince(any(Instant.class))).willReturn(300L);

        UsageSummary s = service.summary();

        assertThat(s.today().date()).isEqualTo("2026-05-31");
        assertThat(s.today().embedCalls()).isEqualTo(43);
        assertThat(s.today().successes()).isEqualTo(40);
        assertThat(s.today().failures()).isEqualTo(3);
        assertThat(s.today().tokensTotal()).isEqualTo(12_000L);
        assertThat(s.today().byOutcome()).containsEntry("SUCCESS", 40L);

        assertThat(s.last7Days().embedCalls()).isEqualTo(300);
        assertThat(s.last7Days().tokensTotal()).isEqualTo(80_000L);

        assertThat(s.limits().dailyCap()).isEqualTo(1000);
        assertThat(s.limits().remainingToday()).isEqualTo(960);
        assertThat(s.limits().killSwitchEnabled()).isFalse();
    }

    @Test
    void 킬스위치가_꺼져있으면_limits_killSwitchEnabled_true() {
        UsageProperties killed = new UsageProperties(false, 1000);
        UsageQueryService svc = new UsageQueryService(repo, killed, clock);
        given(repo.countByOutcomeSince(any(Instant.class))).willReturn(Map.of());
        given(repo.tokensSuccessSince(any(Instant.class))).willReturn(0L, 0L);
        given(repo.countSince(any(Instant.class))).willReturn(0L);

        UsageSummary s = svc.summary();

        assertThat(s.limits().killSwitchEnabled()).isTrue();
    }

    @Test
    void successes가_dailyCap을_넘으면_remainingToday는_0으로_clamp() {
        UsageProperties tight = new UsageProperties(true, 10);
        UsageQueryService svc = new UsageQueryService(repo, tight, clock);
        given(repo.countByOutcomeSince(any(Instant.class)))
                .willReturn(Map.of("SUCCESS", 15L));
        given(repo.tokensSuccessSince(any(Instant.class))).willReturn(0L, 0L);
        given(repo.countSince(any(Instant.class))).willReturn(0L);

        UsageSummary s = svc.summary();

        assertThat(s.limits().remainingToday()).isZero();
    }

    private static <T> T any(Class<T> ignored) {
        return org.mockito.ArgumentMatchers.any();
    }
}
