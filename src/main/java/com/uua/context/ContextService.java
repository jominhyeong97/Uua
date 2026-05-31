package com.uua.context;

import com.uua.embedding.EmbeddingClient;
import com.uua.memory.MemoryJdbcRepository;
import com.uua.memory.MemoryJdbcRepository.SearchHit;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 읽기 경로 오케스트레이션 (단계 ③ PRD §6 / DESIGN.md "v1 구체 스펙"):
 * <ol>
 *   <li>query → EmbeddingClient.embed (단계 ② 그대로 재사용, 실패 시 EmbeddingException → 503)</li>
 *   <li>repo.search(projectKey, qVec, topK) → 코사인 거리 오름차순 top-K</li>
 *   <li>finalScore = (1 - distance) + 0.1 * exp(-ageDays / 30)</li>
 *   <li>finalScore 내림차순 정렬 후 그리디로 token 예산 안에서 누적 — 초과 직전에 멈춤</li>
 *   <li>pack 문자열 "다음은 관련 메모리입니다:\n[1] ...\n[2] ..." 조립</li>
 * </ol>
 *
 * Clock 주입은 테스트에서 "now"를 고정해 recency 점수를 단정 가능하게 하려는 용도.
 */
@Service
public class ContextService {

    private static final double RECENCY_WEIGHT = 0.1;
    private static final double RECENCY_TAU_DAYS = 30.0;
    private static final String PACK_HEADER = "다음은 관련 메모리입니다:";

    private final EmbeddingClient embeddingClient;
    private final MemoryJdbcRepository jdbcRepository;
    private final Clock clock;

    public ContextService(EmbeddingClient embeddingClient,
                          MemoryJdbcRepository jdbcRepository,
                          Clock clock) {
        this.embeddingClient = embeddingClient;
        this.jdbcRepository = jdbcRepository;
        this.clock = clock;
    }

    public ContextResponse buildPack(ContextRequest req) {
        int maxTokens = req.maxTokensOrDefault();
        int topK = req.topKOrDefault();

        // 1) 질문 임베딩 — 실패 시 EmbeddingException 그대로 전파(503으로 매핑).
        float[] qVec = embeddingClient.embed(req.query());

        // 2) top-K 후보. project_key 필터로 다른 프로젝트 격리.
        List<SearchHit> hits = jdbcRepository.search(req.projectKey(), qVec, topK);
        if (hits.isEmpty()) {
            return new ContextResponse("", List.of(), 0, maxTokens);
        }

        // 3) finalScore 계산 후 내림차순. now는 Clock 주입값.
        Instant now = Instant.now(clock);
        List<Scored> scored = new ArrayList<>(hits.size());
        for (SearchHit h : hits) {
            double sim = 1.0 - h.distance();
            double ageDays = ageInDays(now, h.createdAt());
            double recency = Math.exp(-ageDays / RECENCY_TAU_DAYS);
            double finalScore = sim + RECENCY_WEIGHT * recency;
            scored.add(new Scored(h, finalScore));
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        // 4) 그리디 — 누적 token이 maxTokens 이하인 동안 위에서부터 포함.
        // PRD: "예산 초과 직전에 멈춤" → 더 작은 다음 후보를 끼우진 않는다(구현 단순화 + DESIGN.md 명세).
        List<Scored> picked = new ArrayList<>();
        int used = 0;
        for (Scored s : scored) {
            int next = used + s.hit().tokenCount();
            if (next > maxTokens) break;
            picked.add(s);
            used = next;
        }

        // 5) pack 조립 + items 매핑.
        List<ContextResponse.ContextItem> items = new ArrayList<>(picked.size());
        StringBuilder pack = new StringBuilder(PACK_HEADER);
        int idx = 1;
        for (Scored s : picked) {
            pack.append('\n').append('[').append(idx).append("] ").append(s.hit().text());
            items.add(new ContextResponse.ContextItem(
                    s.hit().text(),
                    "memory:" + s.hit().id(),
                    s.hit().createdAt(),
                    s.score()
            ));
            idx++;
        }
        // 선택된 게 0개면 pack은 헤더만 남는 게 어색하므로 빈 문자열로 통일.
        String packStr = picked.isEmpty() ? "" : pack.toString();

        return new ContextResponse(packStr, items, used, maxTokens);
    }

    private static double ageInDays(Instant now, Instant createdAt) {
        // 미래 시각(시계 어긋남)도 음수 age로 두면 recency가 1보다 커지므로 0으로 클램프.
        long secs = Duration.between(createdAt, now).getSeconds();
        if (secs < 0) secs = 0;
        return secs / 86_400.0;
    }

    /** 정렬용 내부 record — 외부 노출 안 함. */
    private record Scored(SearchHit hit, double score) {}
}
