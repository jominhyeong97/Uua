package com.uua.embedding;

import com.uua.usage.UsageLimiter;
import com.uua.usage.UsageProperties;
import com.uua.usage.UsageRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 단계 ⑤ 비용/남용 가드 데코레이터.
 *
 * 모든 외부 임베딩 호출은 이 클래스를 통과한다:
 * <ol>
 *   <li>킬스위치 — usage.embedding-enabled=false면 외부 호출 전에 차단</li>
 *   <li>일일 상한 — 오늘 SUCCESS 카운트가 dailyCap 이상이면 차단</li>
 *   <li>실제 Gemini 호출 위임</li>
 *   <li>성공/실패 무관 usage_log에 1행 기록 (REQUIRES_NEW)</li>
 * </ol>
 *
 * MemoryService/ContextService/IngestService는 {@link EmbeddingClient}만 주입받으므로
 * 이 데코레이터 도입 시 호출자 코드는 한 줄도 바뀌지 않는다.
 */
@Component
public class UsageGuardedEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(UsageGuardedEmbeddingClient.class);
    private static final String OP = "embed";

    private final GeminiEmbeddingClient delegate;
    private final UsageProperties usageProps;
    private final EmbeddingProperties embProps;
    private final UsageLimiter limiter;
    private final UsageRecorder recorder;

    public UsageGuardedEmbeddingClient(GeminiEmbeddingClient delegate,
                                       UsageProperties usageProps,
                                       EmbeddingProperties embProps,
                                       UsageLimiter limiter,
                                       UsageRecorder recorder) {
        this.delegate = delegate;
        this.usageProps = usageProps;
        this.embProps = embProps;
        this.limiter = limiter;
        this.recorder = recorder;
    }

    @Override
    public float[] embed(String text) {
        int approxTokens = text == null ? 0 : text.length() / 4;

        // 1) 킬스위치 — 외부 호출 전에 차단. usage_log엔 KILLED로 기록(어떤 호출이 차단됐는지 추적).
        if (!usageProps.embeddingEnabled()) {
            log.info("guard.killed");
            recorder.record(OP, embProps.model(), approxTokens, 0,
                    EmbeddingException.Reason.KILLED.name());
            throw new EmbeddingException(EmbeddingException.Reason.KILLED,
                    "embedding disabled by kill switch");
        }

        // 2) 일일 상한 — Limiter가 직접 EmbeddingException(DAILY_LIMIT)을 던진다.
        try {
            limiter.check();
        } catch (EmbeddingException e) {
            log.info("guard.daily_limit");
            recorder.record(OP, embProps.model(), approxTokens, 0, e.reason().name());
            throw e;
        }

        // 3) 실제 호출. 4) 결과를 outcome으로 기록.
        long start = System.nanoTime();
        try {
            float[] vec = delegate.embed(text);
            int latencyMs = elapsedMs(start);
            recorder.record(OP, embProps.model(), approxTokens, latencyMs, "SUCCESS");
            return vec;
        } catch (EmbeddingException e) {
            int latencyMs = elapsedMs(start);
            recorder.record(OP, embProps.model(), approxTokens, latencyMs, e.reason().name());
            throw e;
        }
    }

    private static int elapsedMs(long startNanos) {
        return (int) ((System.nanoTime() - startNanos) / 1_000_000);
    }
}
