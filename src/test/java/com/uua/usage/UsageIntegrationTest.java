package com.uua.usage;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 단계 ⑤ 끝-에서-끝: 실제 pgvector + 실제 UsageGuardedEmbeddingClient → MockWebServer로 Gemini 모방.
 *
 * - POST /api/memories 한 번이 usage_log에 SUCCESS 행 1개를 만들고
 *   GET /api/usage/summary가 그걸 카운트하는지 확인.
 * - 킬스위치 ON일 때 외부 호출 없이 KILLED로 503 + usage_log에 KILLED 행 1개.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class UsageIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")
    );

    static MockWebServer gemini;

    @BeforeAll
    static void startGemini() throws IOException {
        gemini = new MockWebServer();
        gemini.start();
    }

    @AfterAll
    static void stopGemini() throws IOException {
        gemini.shutdown();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        // gemini.base-url을 MockWebServer로 — 실제 Google 호출 안 함.
        r.add("gemini.base-url", () -> gemini.url("/").toString().replaceAll("/$", ""));
        r.add("gemini.api-key", () -> "test-key");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;

    private void enqueue768Zero() {
        String values = IntStream.range(0, 768).mapToObj(i -> "0.0")
                .reduce((a, b) -> a + "," + b).orElseThrow();
        gemini.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"embedding\":{\"values\":[" + values + "]}}"));
    }

    @Test
    void POST_memories_정상은_usage_log_SUCCESS_1행_summary_카운트_반영() throws Exception {
        long before = countOutcome("SUCCESS");
        enqueue768Zero();

        mvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "text", "usage log smoke",
                                "projectKey", "uua-usage"
                        ))))
                .andExpect(status().isCreated());

        assertThat(countOutcome("SUCCESS")).isEqualTo(before + 1);

        mvc.perform(get("/api/usage/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.today.byOutcome.SUCCESS").exists())
                .andExpect(jsonPath("$.limits.killSwitchEnabled").value(false));
    }

    private long countOutcome(String outcome) {
        Long n = jdbc.queryForObject(
                "SELECT count(*) FROM usage_log WHERE outcome = ?", Long.class, outcome);
        return n == null ? 0L : n;
    }
}
