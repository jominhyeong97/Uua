package com.uua.usage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 임베딩 호출 결과를 usage_log에 1행 기록.
 *
 * <p>REQUIRES_NEW: 호출자(MemoryService 등)의 트랜잭션이 임베딩 실패로 롤백되더라도
 * 로그는 별도 트랜잭션으로 커밋되어야 한다 — 그게 "비용 추적"의 본분.
 *
 * <p>로깅 자체가 실패해도 본 요청은 영향받지 않게 try/catch로 삼킨다(경고만 남김).
 * 로그 안 남는 게 호출 실패하는 것보다 낫다.
 *
 * <p>self-call 함정 회피: 다른 빈({@link com.uua.embedding.UsageGuardedEmbeddingClient})에서 호출되므로
 * Spring AOP 프록시가 정상 작동한다.
 */
@Component
public class UsageRecorder {

    private static final Logger log = LoggerFactory.getLogger(UsageRecorder.class);

    private final UsageJdbcRepository repo;

    public UsageRecorder(UsageJdbcRepository repo) {
        this.repo = repo;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String op, String model, int tokenCount, int latencyMs, String outcome) {
        try {
            repo.insert(op, model, tokenCount, latencyMs, outcome);
        } catch (DataAccessException e) {
            // 로깅 실패는 호출자에게 전파하지 않음 — 본 요청을 죽이면 본말이 전도된다.
            log.warn("usage_log insert failed outcome={} cause={}", outcome, e.getMessage());
        }
    }
}
