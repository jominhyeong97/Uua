package com.uua.memory;

import com.uua.embedding.EmbeddingClient;
import com.uua.embedding.EmbeddingProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쓰기 경로 오케스트레이션: 임베딩 → INSERT를 한 트랜잭션으로 묶는다.
 *
 * 원자성(PRD §5): 임베딩 호출이 EmbeddingException을 던지면 INSERT까지 도달하지 않으므로
 * 자동으로 행이 생기지 않는다 — v1엔 재시도 큐가 없어 이대로 503으로 끝난다.
 *
 * EmbeddingException은 잡지 않고 그대로 전파 — GlobalExceptionHandler가 503으로 매핑.
 */
@Service
public class MemoryService {

    private final EmbeddingClient embeddingClient;
    private final MemoryJdbcRepository jdbcRepository;
    private final EmbeddingProperties props;

    public MemoryService(EmbeddingClient embeddingClient,
                         MemoryJdbcRepository jdbcRepository,
                         EmbeddingProperties props) {
        this.embeddingClient = embeddingClient;
        this.jdbcRepository = jdbcRepository;
        this.props = props;
    }

    @Transactional
    public CreateMemoryResponse create(CreateMemoryRequest req) {
        // 1) 외부 호출 — 실패시 여기서 EmbeddingException 던져 트랜잭션 롤백.
        float[] vec = embeddingClient.embed(req.text());

        // 2) 토큰 근사: 정확한 토크나이저 대신 글자수/4 (PRD §6).
        int tokens = req.text().length() / 4;

        // 3) 단일 INSERT RETURNING — 행이 만들어지고 id/created_at만 받아옴.
        MemoryJdbcRepository.Inserted inserted = jdbcRepository.insert(
                req.projectKey(),
                req.sessionKey(),
                req.text(),
                vec,
                "MANUAL",       // v1에선 MANUAL만 발생 — INGEST는 단계 ④에서 추가
                props.model(),
                tokens
        );

        return new CreateMemoryResponse(
                inserted.id(),
                req.projectKey(),
                inserted.createdAt(),
                tokens
        );
    }
}
