package com.uua.usage;

import com.uua.embedding.EmbeddingException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UsageLimiterTest {

    private static final Instant NOW = Instant.parse("2026-05-31T08:30:00Z");

    private final UsageJdbcRepository repo = mock(UsageJdbcRepository.class);
    private final UsageProperties props = new UsageProperties(true, 1000);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final UsageLimiter limiter = new UsageLimiter(repo, props, clock);

    @Test
    void 한도_미만이면_부수효과_없음() {
        given(repo.countSuccessSince(any(Instant.class))).willReturn(999L);

        assertThatCode(limiter::check).doesNotThrowAnyException();
    }

    @Test
    void 한도와_같으면_DAILY_LIMIT() {
        given(repo.countSuccessSince(any(Instant.class))).willReturn(1000L);

        assertThatThrownBy(limiter::check)
                .isInstanceOf(EmbeddingException.class)
                .satisfies(e -> assertThat(((EmbeddingException) e).reason())
                        .isEqualTo(EmbeddingException.Reason.DAILY_LIMIT));
    }

    @Test
    void 한도_초과여도_DAILY_LIMIT() {
        given(repo.countSuccessSince(any(Instant.class))).willReturn(1500L);

        assertThatThrownBy(limiter::check)
                .isInstanceOf(EmbeddingException.class);
    }

    @Test
    void countSuccessSince는_오늘_UTC_자정_기준으로_호출된다() {
        given(repo.countSuccessSince(any(Instant.class))).willReturn(0L);

        limiter.check();

        ArgumentCaptor<Instant> sinceCap = ArgumentCaptor.forClass(Instant.class);
        verify(repo).countSuccessSince(sinceCap.capture());
        Instant expectedTodayStart = Instant.parse("2026-05-31T00:00:00Z");
        assertThat(sinceCap.getValue()).isEqualTo(expectedTodayStart);
    }
}
