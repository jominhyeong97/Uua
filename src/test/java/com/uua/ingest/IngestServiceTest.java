package com.uua.ingest;

import com.uua.embedding.EmbeddingClient;
import com.uua.embedding.EmbeddingException;
import com.uua.embedding.EmbeddingProperties;
import com.uua.memory.MemoryJdbcRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * IngestService 단위 테스트.
 *
 * - 진짜 DB·임베딩 호출 X (mock)
 * - TransactionTemplate은 no-op PlatformTransactionManager로 우회 — 콜백 실행/예외 전파만 검증
 * - Sleeper는 호출 millis만 기록(잠들지 않음)
 */
class IngestServiceTest {

    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final MemoryJdbcRepository repo = mock(MemoryJdbcRepository.class);
    private final EmbeddingProperties embProps = new EmbeddingProperties(
            "k", "u", "gemini-embedding-001", 10, 768);
    private final IngestProperties ingProps = new IngestProperties(200L, 2000);

    private final List<Long> sleepCalls = new ArrayList<>();
    private final Sleeper recordingSleeper = sleepCalls::add;

    private final PlatformTransactionManager noopTx = new NoopTxManager();

    private final IngestService service = new IngestService(
            embeddingClient, repo, embProps, ingProps, recordingSleeper, noopTx);

    @Test
    void 빈_text는_chunks_0_과_빈_ids_응답_부수효과_없음() {
        IngestRequest req = new IngestRequest("", "uua");
        // 컨트롤러에서 막히겠지만 서비스 단독 호출 시 0 청크로 안전 처리되는지 확인.

        IngestResponse resp = service.ingest("s-1", req);

        assertThat(resp.chunks()).isZero();
        assertThat(resp.ids()).isEmpty();
        assertThat(resp.tokensTotal()).isZero();
        assertThat(sleepCalls).isEmpty();
        verify(embeddingClient, times(0)).embed(any());
    }

    @Test
    void 정상_2청크면_embed_2회_insert_2회_sleep_1회_throttle_millis로() {
        // 4000자 → 2000자 청크 2개. 첫 청크 후엔 sleep 안 함, 두 번째 앞에 1번.
        String text = "x".repeat(4000);
        given(embeddingClient.embed(any())).willReturn(new float[768]);
        given(repo.insert(anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyInt()))
                .willReturn(new MemoryJdbcRepository.Inserted(101L, Instant.now()))
                .willReturn(new MemoryJdbcRepository.Inserted(102L, Instant.now()));

        IngestResponse resp = service.ingest("s-1", new IngestRequest(text, "uua"));

        assertThat(resp.chunks()).isEqualTo(2);
        assertThat(resp.ids()).containsExactly(101L, 102L);
        assertThat(resp.tokensTotal()).isEqualTo(2000 / 4 * 2);
        verify(embeddingClient, times(2)).embed(any());
        verify(repo, times(2)).insert(eq("uua"), eq("s-1"), anyString(), any(),
                eq("INGEST"), eq("gemini-embedding-001"), eq(500));
        assertThat(sleepCalls).containsExactly(200L);
    }

    @Test
    void 중간_청크_임베딩_실패는_부분커밋_committed_failedAt() {
        // 3 청크: 1번째 성공, 2번째 실패.
        String text = "x".repeat(6000);
        given(embeddingClient.embed(any()))
                .willReturn(new float[768]) // 청크 1 OK
                .willThrow(new EmbeddingException(EmbeddingException.Reason.RATE_LIMIT, "429"));
        given(repo.insert(anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyInt()))
                .willReturn(new MemoryJdbcRepository.Inserted(101L, Instant.now()));

        assertThatThrownBy(() -> service.ingest("s-1", new IngestRequest(text, "uua")))
                .isInstanceOf(IngestPartialFailure.class)
                .satisfies(ex -> {
                    IngestPartialFailure f = (IngestPartialFailure) ex;
                    assertThat(f.committed()).isEqualTo(1);
                    assertThat(f.failedAt()).isEqualTo(2);
                    assertThat(f.reason()).isEqualTo(EmbeddingException.Reason.RATE_LIMIT);
                });
        // INSERT는 1번만 호출(2번째는 임베딩 단계에서 멈춤).
        verify(repo, times(1)).insert(anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyInt());
    }

    @Test
    void 첫_청크가_바로_실패하면_committed_0() {
        String text = "x".repeat(100);
        willThrow(new EmbeddingException(EmbeddingException.Reason.TIMEOUT, "t"))
                .given(embeddingClient).embed(any());

        assertThatThrownBy(() -> service.ingest("s-1", new IngestRequest(text, "uua")))
                .isInstanceOf(IngestPartialFailure.class)
                .satisfies(ex -> {
                    IngestPartialFailure f = (IngestPartialFailure) ex;
                    assertThat(f.committed()).isZero();
                    assertThat(f.failedAt()).isEqualTo(1);
                });
        verify(repo, times(0)).insert(anyString(), anyString(), anyString(), any(),
                anyString(), anyString(), anyInt());
        assertThat(sleepCalls).isEmpty();
    }

    /** TransactionTemplate이 호출하는 모든 메서드를 no-op 처리. 실제 트랜잭션 동작은 통합 테스트에서. */
    static class NoopTxManager implements PlatformTransactionManager {
        @Override public TransactionStatus getTransaction(TransactionDefinition def) {
            return new SimpleTransactionStatus();
        }
        @Override public void commit(TransactionStatus status) { /* no-op */ }
        @Override public void rollback(TransactionStatus status) { /* no-op */ }
    }
}
