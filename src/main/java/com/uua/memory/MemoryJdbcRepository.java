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
 */
@Repository
public class MemoryJdbcRepository {

    private static final String INSERT_SQL = """
            INSERT INTO memory_item (project_key, session_key, text, embedding, source, model, token_count)
            VALUES (?, ?, ?, CAST(? AS vector(768)), ?, ?, ?)
            RETURNING id, created_at
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
     * 단계 ③에서 사용할 의미검색 자리. 단계 ②에선 미구현.
     *
     * 구현 예정 SQL: ORDER BY embedding &lt;=&gt; CAST(? AS vector(768)) LIMIT ?
     */
    @SuppressWarnings({"unused", "java:S1172"})
    public List<MemoryItem> search(float[] queryVec, int k) {
        throw new UnsupportedOperationException("search()는 단계 ③에서 구현 — 단계 ②에선 호출 금지");
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
}
