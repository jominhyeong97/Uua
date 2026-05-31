package com.uua.context;

import com.uua.embedding.EmbeddingClient;
import com.uua.embedding.EmbeddingException;
import com.uua.memory.MemoryJdbcRepository;
import com.uua.memory.MemoryJdbcRepository.SearchHit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * ContextService의 점수 계산·그리디 조립 로직 단위 테스트.
 * EmbeddingClient + MemoryJdbcRepository는 모두 mock — DB·외부호출 없음.
 *
 * 시간은 Clock.fixed로 고정해 recency 점수를 결정적으로 만든다.
 */
class ContextServiceTest {

    // 현재 시각 고정. 아래 청크들의 createdAt은 이 시각 기준 상대값으로 만든다.
    private static final Instant NOW = Instant.parse("2026-05-31T12:00:00Z");

    private final EmbeddingClient embeddingClient = mock(EmbeddingClient.class);
    private final MemoryJdbcRepository repo = mock(MemoryJdbcRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ContextService service = new ContextService(embeddingClient, repo, clock);

    private ContextRequest req(int maxTokens) {
        return new ContextRequest("어제 결정한 카프카", "uua", maxTokens, 20);
    }

    @Test
    void 결과_0건이면_빈_pack과_items_zero_usedTokens() {
        given(embeddingClient.embed(any())).willReturn(new float[768]);
        given(repo.search(eq("uua"), any(), anyInt())).willReturn(List.of());

        ContextResponse resp = service.buildPack(req(1000));

        assertThat(resp.pack()).isEmpty();
        assertThat(resp.items()).isEmpty();
        assertThat(resp.usedTokens()).isZero();
        assertThat(resp.maxTokens()).isEqualTo(1000);
    }

    @Test
    void finalScore_내림차순으로_정렬되어_pack에_들어간다() {
        given(embeddingClient.embed(any())).willReturn(new float[768]);
        // 두 후보: A는 거리가 멀지만(낮은 sim) 신선, B는 가깝지만 오래됨.
        // sim(A) = 1 - 0.5 = 0.5,  recency(A) = exp(0) = 1,    final ≈ 0.5 + 0.1 = 0.6
        // sim(B) = 1 - 0.1 = 0.9,  recency(B) = exp(-60/30) ≈ 0.135, final ≈ 0.9 + 0.0135 = 0.9135
        SearchHit a = new SearchHit(1, "A 텍스트", NOW, 10, 0.5);
        SearchHit b = new SearchHit(2, "B 텍스트", NOW.minusSeconds(60L * 86_400), 10, 0.1);
        given(repo.search(eq("uua"), any(), anyInt())).willReturn(List.of(a, b));

        ContextResponse resp = service.buildPack(req(1000));

        assertThat(resp.items()).hasSize(2);
        assertThat(resp.items().get(0).text()).isEqualTo("B 텍스트");
        assertThat(resp.items().get(1).text()).isEqualTo("A 텍스트");
        // pack은 같은 순서 + 인용 번호.
        assertThat(resp.pack()).startsWith("다음은 관련 메모리입니다:\n[1] B 텍스트\n[2] A 텍스트");
        assertThat(resp.usedTokens()).isEqualTo(20);
    }

    @Test
    void 토큰_예산을_초과하면_그_청크는_빼고_멈춘다() {
        given(embeddingClient.embed(any())).willReturn(new float[768]);
        // 모두 같은 시각(NOW) → recency 동일. distance만 다르게 둬서 final 순서가 결정됨.
        // 정렬 후 [80, 50, 100] 토큰. maxTokens=100이면 첫 번째(80) 포함 → 50을 더하면 130 > 100이라 멈춤.
        SearchHit big = new SearchHit(1, "BIG", NOW, 80, 0.0);   // sim 1.0 → 최고
        SearchHit mid = new SearchHit(2, "MID", NOW, 50, 0.1);   // sim 0.9
        SearchHit huge = new SearchHit(3, "HUGE", NOW, 100, 0.2);// sim 0.8
        given(repo.search(eq("uua"), any(), anyInt())).willReturn(List.of(big, mid, huge));

        ContextResponse resp = service.buildPack(req(100));

        assertThat(resp.items()).hasSize(1);
        assertThat(resp.items().get(0).text()).isEqualTo("BIG");
        assertThat(resp.usedTokens()).isEqualTo(80);
        assertThat(resp.pack()).isEqualTo("다음은 관련 메모리입니다:\n[1] BIG");
    }

    @Test
    void 임베딩_실패는_그대로_전파되어_컨트롤러에서_503() {
        willThrow(new EmbeddingException(EmbeddingException.Reason.RATE_LIMIT, "429"))
                .given(embeddingClient).embed(any());

        assertThatThrownBy(() -> service.buildPack(req(1000)))
                .isInstanceOf(EmbeddingException.class);
    }

    @Test
    void source_필드는_memory_id_형식이고_score는_유사도_플러스_recency() {
        given(embeddingClient.embed(any())).willReturn(new float[768]);
        SearchHit only = new SearchHit(42, "한 줄 메모", NOW, 5, 0.2);
        given(repo.search(eq("uua"), any(), anyInt())).willReturn(List.of(only));

        ContextResponse resp = service.buildPack(req(1000));

        assertThat(resp.items()).hasSize(1);
        ContextResponse.ContextItem item = resp.items().get(0);
        assertThat(item.source()).isEqualTo("memory:42");
        // sim=0.8, recency=exp(0)=1, final=0.8+0.1=0.9
        assertThat(item.score()).isEqualTo(0.9, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void projectKey와_topK가_repo_search에_그대로_전달된다() {
        given(embeddingClient.embed(any())).willReturn(new float[768]);
        given(repo.search(any(), any(), anyInt())).willReturn(List.of());
        ContextRequest customReq = new ContextRequest("q", "other-proj", null, 7);

        service.buildPack(customReq);

        ArgumentCaptor<String> projectKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> k = ArgumentCaptor.forClass(Integer.class);
        verify(repo).search(projectKey.capture(), any(), k.capture());
        assertThat(projectKey.getValue()).isEqualTo("other-proj");
        assertThat(k.getValue()).isEqualTo(7);
    }
}
