package com.uua.ingest;

import com.uua.embedding.EmbeddingClient;
import com.uua.embedding.EmbeddingException;
import com.uua.embedding.EmbeddingProperties;
import com.uua.memory.MemoryJdbcRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * 단계 ④ ingest 오케스트레이션:
 * <ol>
 *   <li>text → Chunker로 고정창 분할</li>
 *   <li>청크별로 순차 처리: 임베딩 → INSERT(per-chunk TX) → throttle sleep</li>
 *   <li>중간 청크 실패: 이미 커밋된 청크는 유지 + {@link IngestPartialFailure} 던짐 (부분 커밋)</li>
 * </ol>
 *
 * <p>왜 청크당 트랜잭션인가: v1엔 재시도 큐가 없다. 100개 청크 중 마지막 1개가 429에 걸리면
 * 전체 롤백 시 그 ingest는 영구 실패가 된다. RAG는 부분 데이터라도 가치 있으므로 부분 커밋이 정답.
 *
 * <p>왜 TransactionTemplate인가: 같은 클래스 내부 메서드를 @Transactional로 묶어 호출하면
 * Spring AOP 프록시를 우회해 트랜잭션이 적용되지 않는다(self-call 함정).
 * 프로그래매틱 트랜잭션이 가장 단순한 회피책.
 */
@Service
public class IngestService {

    private static final Logger log = LoggerFactory.getLogger(IngestService.class);

    private final EmbeddingClient embeddingClient;
    private final MemoryJdbcRepository repo;
    private final EmbeddingProperties embProps;
    private final IngestProperties ingProps;
    private final Sleeper sleeper;
    private final TransactionTemplate tx;

    public IngestService(EmbeddingClient embeddingClient,
                         MemoryJdbcRepository repo,
                         EmbeddingProperties embProps,
                         IngestProperties ingProps,
                         Sleeper sleeper,
                         PlatformTransactionManager txManager) {
        this.embeddingClient = embeddingClient;
        this.repo = repo;
        this.embProps = embProps;
        this.ingProps = ingProps;
        this.sleeper = sleeper;
        this.tx = new TransactionTemplate(txManager);
    }

    public IngestResponse ingest(String sessionId, IngestRequest req) {
        List<String> chunks = Chunker.chunk(req.text(), ingProps.windowChars());
        List<Long> ids = new ArrayList<>(chunks.size());
        int tokensTotal = 0;

        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) sleeper.sleep(ingProps.throttleMillis());

            String chunkText = chunks.get(i);
            int tokens = chunkText.length() / 4;
            try {
                Long id = tx.execute(status -> {
                    float[] vec = embeddingClient.embed(chunkText);
                    return repo.insert(
                            req.projectKey(),
                            sessionId,
                            chunkText,
                            vec,
                            "INGEST",
                            embProps.model(),
                            tokens
                    ).id();
                });
                ids.add(id);
                tokensTotal += tokens;
            } catch (EmbeddingException e) {
                log.info("ingest.chunk_failed sessionId={} committed={} failedAt={} cause={}",
                        sessionId, ids.size(), i + 1, e.reason());
                throw new IngestPartialFailure(e, ids.size(), i + 1);
            }
        }

        log.info("ingest.completed sessionId={} chunks={} tokens={}",
                sessionId, ids.size(), tokensTotal);
        return new IngestResponse(sessionId, req.projectKey(), ids.size(), ids, tokensTotal);
    }
}
