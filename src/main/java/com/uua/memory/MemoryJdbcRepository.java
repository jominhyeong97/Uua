package com.uua.memory;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * pgvector vector(768) 컬럼을 다루는 전용 리포지터리.
 *
 * v1 쓰기 경로(단계 ②)는 JPA save 두 단계(INSERT + UPDATE)가 불가능하다(NOT NULL 제약).
 * 그래서 INSERT ... RETURNING id, created_at으로 한 번에 처리한다.
 *
 * 읽기 경로(단계 ③)는 ORDER BY embedding &lt;=&gt; q LIMIT k 네이티브 SQL로 top-K를 뽑는다.
 * pgvector의 &lt;=&gt; 는 코사인 거리(0..2)다 — 호출자 ContextService에서 1-d로 유사도 환산.
 */
@Repository
public class MemoryJdbcRepository {

    private static final String INSERT_SQL = """
            INSERT INTO memory_item (project_key, session_key, text, embedding, source, model, token_count)
            VALUES (?, ?, ?, CAST(? AS vector(768)), ?, ?, ?)
            RETURNING id, created_at
            """;

    // 동일 표현을 SELECT와 ORDER BY에서 두 번 평가하지 않도록 컬럼 별칭(distance)으로 한 번만 계산.
    // 인덱스 없이 전수검색이라 사실상 큰 차이는 없지만 가독성·안전성 위주.
    private static final String SEARCH_SQL = """
            SELECT id,
                   text,
                   created_at,
                   token_count,
                   embedding <=> CAST(? AS vector(768)) AS distance
            FROM memory_item
            WHERE project_key = ?
            ORDER BY distance
            LIMIT ?
            """;

    private final JdbcTemplate jdbc;

    public MemoryJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 단일 INSERT RETURNING. 행을 만들고 DB가 채운 id/created_at만 가져온다.
     *
     * @param embedding 길이는 호출자(GeminiEmbeddingClient)가 보장 — 여기서 재검증하지 않음.
     */
    public Inserted insert(String projectKey,
                           String sessionKey,
                           String text,
                           float[] embedding,
                           String source,
                           String model,
                           int tokenCount) {
        String vectorLiteral = toVectorLiteral(embedding);
        return jdbc.execute(INSERT_SQL, (PreparedStatement ps) -> {
            ps.setString(1, projectKey);
            ps.setString(2, sessionKey);
            ps.setString(3, text);
            ps.setString(4, vectorLiteral);
            ps.setString(5, source);
            ps.setString(6, model);
            ps.setInt(7, tokenCount);
            try (var rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("INSERT RETURNING did not return a row");
                }
                long id = rs.getLong("id");
                Instant createdAt = rs.getTimestamp("created_at").toInstant();
                return new Inserted(id, createdAt);
            }
        });
    }

    /**
     * 단계 ③ 의미검색 top-K. project_key로 다른 프로젝트 메모리와 격리.
     *
     * @param projectKey 검색 대상 프로젝트(필수)
     * @param queryVec   질문 임베딩 — 길이는 호출자가 보장(검증 안 함)
     * @param k          최대 결과 수(>0)
     * @return cosine distance 오름차순(=가까운 순) 리스트. 결과 0건이면 빈 리스트.
     */
    public List<SearchHit> search(String projectKey, float[] queryVec, int k) {
        String vectorLiteral = toVectorLiteral(queryVec);
        return jdbc.query(
                SEARCH_SQL,
                (rs, rowNum) -> new SearchHit(
                        rs.getLong("id"),
                        rs.getString("text"),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getInt("token_count"),
                        rs.getDouble("distance")
                ),
                vectorLiteral, projectKey, k
        );
    }

    /**
     * pgvector는 벡터 리터럴을 "[v1,v2,...]" 문자열로 받는다.
     * 로케일과 무관하게 점(.) 소수점을 쓰도록 Locale.ROOT로 포맷.
     */
    static String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 12);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            // %s는 Float.toString과 같은 표현 → "0.0", "-1.234E-5" 형태. pgvector 파서가 허용.
            sb.append(String.format(Locale.ROOT, "%s", vec[i]));
        }
        sb.append(']');
        return sb.toString();
    }

    /** insert()의 반환값 — DB가 생성한 id와 created_at. */
    public record Inserted(long id, Instant createdAt) {}

    /**
     * search()의 단일 행. distance는 pgvector 코사인 거리(0=동일, 2=정반대).
     * 유사도(0..1)는 1 - distance/2가 정석이지만, 정규화 임베딩에선 1-d로도 충분 — 호출자가 결정.
     */
    public record SearchHit(long id, String text, Instant createdAt, int tokenCount, double distance) {}
}
