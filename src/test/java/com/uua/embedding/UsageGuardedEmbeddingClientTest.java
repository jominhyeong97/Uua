package com.uua.embedding;

import com.uua.usage.UsageLimiter;
import com.uua.usage.UsageProperties;
import com.uua.usage.UsageRecorder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * UsageGuardedEmbeddingClient의 4가지 경로:
 *  - KILLED: delegate 호출 0회 + recorder 1회(KILLED)
 *  - DAILY_LIMIT: delegate 호출 0회 + recorder 1회(DAILY_LIMIT)
 *  - SUCCESS: delegate 호출 1회 + recorder 1회(SUCCESS)
 *  - FAILURE: delegate가 EmbeddingException → recorder 1회(해당 reason)
 */
class UsageGuardedEmbeddingClientTest {

    private final GeminiEmbeddingClient delegate = mock(GeminiEmbeddingClient.class);
    private final UsageLimiter limiter = mock(UsageLimiter.class);
    private final UsageRecorder recorder = mock(UsageRecorder.class);
    private final EmbeddingProperties embProps = new EmbeddingProperties(
            "k", "u", "gemini-embedding-001", 10, 768);

    private UsageGuardedEmbeddingClient newClient(boolean enabled) {
        UsageProperties usageProps = new UsageProperties(enabled, 1000);
        return new UsageGuardedEmbeddingClient(delegate, usageProps, embProps, limiter, recorder);
    }

    @Test
    void 킬스위치_OFF면_외부호출_없이_KILLED_기록_후_503() {
        UsageGuardedEmbeddingClient client = newClient(false);

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(EmbeddingException.class)
                .satisfies(e -> assertThat(((EmbeddingException) e).reason())
                        .isEqualTo(EmbeddingException.Reason.KILLED));

        verify(delegate, never()).embed(any());
        verify(limiter, never()).check();
        verify(recorder).record(eq("embed"), eq("gemini-embedding-001"),
                anyInt(), eq(0), eq("KILLED"));
    }

    @Test
    void DAILY_LIMIT은_외부호출_없이_그대로_전파되며_DAILY_LIMIT_기록() {
        UsageGuardedEmbeddingClient client = newClient(true);
        willThrow(new EmbeddingException(EmbeddingException.Reason.DAILY_LIMIT, "cap"))
                .given(limiter).check();

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(EmbeddingException.class)
                .satisfies(e -> assertThat(((EmbeddingException) e).reason())
                        .isEqualTo(EmbeddingException.Reason.DAILY_LIMIT));

        verify(delegate, never()).embed(any());
        verify(recorder).record(eq("embed"), anyString(), anyInt(), eq(0), eq("DAILY_LIMIT"));
    }

    @Test
    void SUCCESS면_delegate_결과_그대로_반환하고_SUCCESS_기록() {
        UsageGuardedEmbeddingClient client = newClient(true);
        float[] vec = new float[768];
        given(delegate.embed("hello")).willReturn(vec);

        float[] out = client.embed("hello");

        assertThat(out).isSameAs(vec);
        verify(limiter, times(1)).check();
        verify(recorder).record(eq("embed"), eq("gemini-embedding-001"), eq(1), anyInt(), eq("SUCCESS"));
    }

    @Test
    void delegate_실패면_그_reason으로_기록후_예외_재전파() {
        UsageGuardedEmbeddingClient client = newClient(true);
        willThrow(new EmbeddingException(EmbeddingException.Reason.RATE_LIMIT, "429"))
                .given(delegate).embed(any());

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(EmbeddingException.class)
                .satisfies(e -> assertThat(((EmbeddingException) e).reason())
                        .isEqualTo(EmbeddingException.Reason.RATE_LIMIT));

        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        verify(recorder).record(anyString(), anyString(), anyInt(), anyInt(), outcome.capture());
        assertThat(outcome.getValue()).isEqualTo("RATE_LIMIT");
    }

    @Test
    void approxTokens는_입력_길이의_4분의1() {
        UsageGuardedEmbeddingClient client = newClient(true);
        given(delegate.embed(any())).willReturn(new float[768]);

        client.embed("a".repeat(100));

        verify(recorder).record(eq("embed"), anyString(), eq(25), anyInt(), eq("SUCCESS"));
    }
}
