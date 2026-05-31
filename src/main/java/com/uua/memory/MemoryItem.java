package com.uua.memory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * memory_item 테이블 매핑. 단계 ②의 SELECT/list 경로(단계 ③ 예정)에서 사용된다.
 *
 * 쓰기 경로는 JPA가 아닌 {@link MemoryJdbcRepository}의 단일 INSERT RETURNING으로 처리한다 —
 * pgvector vector(768) 컬럼은 JPA가 다룰 수 없기 때문(별도 UserType이 필요하지만 v1엔 비용 대비 이득 적음).
 *
 * embedding 컬럼은 의도적으로 {@link Transient}로 두어 Hibernate가 손대지 않게 한다.
 * ddl-auto=validate는 "엔티티가 선언한 컬럼이 DB에 존재하는지"만 보고, DB의 추가 컬럼은 무시한다.
 */
@Entity
@Table(name = "memory_item")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "project_key", nullable = false)
    private String projectKey;

    @Column(name = "session_key")
    private String sessionKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String text;

    @Column(nullable = false, length = 64)
    private String source;

    @Column(nullable = false, length = 128)
    private String model;

    @Column(name = "token_count", nullable = false)
    private int tokenCount;

    // DB의 DEFAULT now()로 채워진다. JPA로는 절대 쓰지 않음.
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    // v1 미사용(Approach B 자리). 읽기만 가능.
    @Column(name = "supersedes_id", insertable = false, updatable = false)
    private Long supersedesId;

    // pgvector vector(768) — Hibernate가 매핑하지 않도록 Transient. 실제 read/write는 JdbcTemplate.
    @Transient
    @SuppressWarnings("unused")
    private float[] embedding;

    /** JdbcTemplate의 RowMapper에서 객체를 만들 때 호출 — 외부에서는 쓰지 않는다. */
    static MemoryItem reconstruct(Long id, String projectKey, String sessionKey, String text,
                                  String source, String model, int tokenCount, Instant createdAt) {
        MemoryItem m = new MemoryItem();
        m.id = id;
        m.projectKey = projectKey;
        m.sessionKey = sessionKey;
        m.text = text;
        m.source = source;
        m.model = model;
        m.tokenCount = tokenCount;
        m.createdAt = createdAt;
        return m;
    }
}
