package com.uua.ingest;

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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 단계 ④ 끝-에서-끝 검증: 실제 pgvector 컨테이너에 청크들이 INSERT되고 source="INGEST"로 저장되는지.
 *
 * - throttle은 0으로 덮어써 테스트가 느려지지 않게 함.
 * - EmbeddingClient는 fake로 교체 — 실제 Gemini 호출 없음.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "ingest.throttle-millis=0")
@AutoConfigureMockMvc
@Testcontainers
class IngestIntegrationTest {

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
            return text -> new float[768]; // 모든 청크에 동일 영벡터
        }
    }

    @Test
    void POST_ingest_4000자는_2청크로_나뉘어_저장되고_source는_INGEST() throws Exception {
        // 정확히 2 청크(2000+2000)가 되도록 4000자.
        String text = "x".repeat(4000);
        String body = json.writeValueAsString(Map.of(
                "text", text,
                "projectKey", "ingest-it"
        ));

        mvc.perform(post("/api/sessions/{sessionId}/ingest", "s-it-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunks").value(2))
                .andExpect(jsonPath("$.ids.length()").value(2))
                .andExpect(jsonPath("$.tokensTotal").value(500 + 500));

        // DB 직접 검증 — 정확히 2행, source=INGEST, session_key 일치.
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT session_key, source, token_count, length(text) AS chars "
                        + "FROM memory_item WHERE project_key = ? ORDER BY id",
                "ingest-it"
        );
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("session_key")).isEqualTo("s-it-1");
            assertThat(r.get("source")).isEqualTo("INGEST");
            assertThat(((Number) r.get("token_count")).intValue()).isEqualTo(500);
            assertThat(((Number) r.get("chars")).intValue()).isEqualTo(2000);
        });
    }

    @Test
    void POST_ingest_4001자는_3청크로_나뉘고_마지막_청크는_1자() throws Exception {
        String text = "y".repeat(4001);
        String body = json.writeValueAsString(Map.of(
                "text", text,
                "projectKey", "ingest-it2"
        ));

        mvc.perform(post("/api/sessions/{sessionId}/ingest", "s-it-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chunks").value(3));

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT length(text) AS chars FROM memory_item WHERE project_key = ? ORDER BY id",
                "ingest-it2"
        );
        assertThat(rows).hasSize(3);
        assertThat(((Number) rows.get(0).get("chars")).intValue()).isEqualTo(2000);
        assertThat(((Number) rows.get(1).get("chars")).intValue()).isEqualTo(2000);
        assertThat(((Number) rows.get(2).get("chars")).intValue()).isEqualTo(1);
    }
}
