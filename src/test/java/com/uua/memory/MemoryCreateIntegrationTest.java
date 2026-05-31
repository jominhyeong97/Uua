package com.uua.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uua.embedding.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 단계 ②의 끝-에서-끝 검증: 진짜 pgvector 컨테이너에 INSERT가 실제로 들어가는지.
 *
 * 이 테스트는 Docker(Docker Desktop) 실행을 전제로 한다. 컨테이너가 pgvector/pgvector:pg16을
 * pull받아 띄우고, Flyway V1__init.sql이 적용된 뒤 컨트롤러 호출이 행을 만드는 걸 확인한다.
 *
 * EmbeddingClient는 @TestConfiguration으로 고정 float[768]을 반환하는 빈으로 대체 —
 * 실제 Gemini는 호출하지 않는다(무료 RPM 절약 + Google 학습 데이터 사용 회피).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class MemoryCreateIntegrationTest {

    // pgvector를 포함한 postgres 이미지 — Testcontainers의 PostgreSQLContainer가 호환되도록
    // asCompatibleSubstituteFor("postgres")로 표시한다.
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingClient fakeEmbeddingClient() {
            // 항상 같은 768차원 영벡터 반환 — Gemini 호출 없음.
            return text -> new float[768];
        }
    }

    @Test
    void POST_memories_정상_요청은_pgvector_테이블에_행을_저장한다() throws Exception {
        // given — 시작 시점의 행 개수
        Integer before = jdbc.queryForObject("SELECT COUNT(*) FROM memory_item", Integer.class);
        String body = json.writeValueAsString(Map.of(
                "text", "integration test memory",
                "projectKey", "uua-it",
                "sessionKey", "it-session-1"
        ));

        // when
        mvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated());

        // then — 행이 1개 늘었고 embedding 컬럼도 NOT NULL이 만족됨(쿼리 자체가 NOT NULL을 깨면 INSERT 실패).
        Integer after = jdbc.queryForObject("SELECT COUNT(*) FROM memory_item", Integer.class);
        assertThat(after).isEqualTo((before == null ? 0 : before) + 1);

        // 핵심 컬럼이 우리가 보낸 값과 일치하는지
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT project_key, session_key, text, source, model, token_count FROM memory_item "
                        + "WHERE project_key = ? ORDER BY id DESC LIMIT 1",
                "uua-it"
        );
        assertThat(row.get("text")).isEqualTo("integration test memory");
        assertThat(row.get("session_key")).isEqualTo("it-session-1");
        assertThat(row.get("source")).isEqualTo("MANUAL");
    }
}
