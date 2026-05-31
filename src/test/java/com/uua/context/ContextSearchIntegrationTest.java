package com.uua.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uua.embedding.EmbeddingClient;
import com.uua.memory.MemoryJdbcRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 단계 ③의 끝-에서-끝 검증: 실제 pgvector 컨테이너에 행 3개를 넣고
 * POST /api/context가 의미적으로 가장 가까운 청크를 1순위로 돌려주는지 확인.
 *
 * EmbeddingClient는 텍스트 키워드별로 결정적인 768차원 벡터를 만드는 fake로 교체 — 실제 Gemini 호출 없음.
 * 같은 키워드를 가진 쿼리/저장 텍스트는 동일 벡터(=거리 0)가 되도록 매핑한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Testcontainers
class ContextSearchIntegrationTest {

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
    @Autowired MemoryJdbcRepository repo;

    /**
     * 텍스트에 포함된 키워드 하나로 768차원의 "원-핫 비슷한" 벡터를 만든다.
     * 같은 키워드 → 같은 벡터 → 코사인 거리 0. 다른 키워드 → 다른 인덱스에 1.0 → 거리 1.
     *
     * 키워드: "kafka" / "react" / "spring" — 인덱스 0/1/2를 사용.
     */
    @TestConfiguration
    static class FakeEmbeddingConfig {
        @Bean
        @Primary
        EmbeddingClient fakeEmbeddingClient() {
            return text -> {
                float[] v = new float[768];
                String lower = text.toLowerCase();
                if (lower.contains("kafka")) v[0] = 1.0f;
                else if (lower.contains("react")) v[1] = 1.0f;
                else if (lower.contains("spring")) v[2] = 1.0f;
                else v[767] = 1.0f; // 어디에도 매칭되지 않으면 별개 차원
                return v;
            };
        }
    }

    @Test
    void POST_context_의미적으로_가장_가까운_청크가_pack의_첫_번째() throws Exception {
        // given — 3개의 메모리를 직접 INSERT(텍스트별 fake 벡터로). projectKey는 모두 같지만 의미는 셋이 다 다름.
        repo.insert("uua-it3", "s1", "kafka 보상 트랜잭션 결정",
                vec(0), "MANUAL", "fake-001", 10);
        repo.insert("uua-it3", "s1", "react 컴포넌트 재사용 패턴",
                vec(1), "MANUAL", "fake-001", 10);
        repo.insert("uua-it3", "s1", "spring 부팅 시 Flyway 적용 순서",
                vec(2), "MANUAL", "fake-001", 10);

        String body = json.writeValueAsString(Map.of(
                "query", "어제 결정한 kafka 보상 방식이 뭐였지?",
                "projectKey", "uua-it3",
                "maxTokens", 1000,
                "topK", 10
        ));

        // when
        MvcResult result = mvc.perform(post("/api/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        // then — items[0].text는 kafka 청크여야 함(거리 0).
        JsonNode resp = json.readTree(result.getResponse().getContentAsString());
        assertThat(resp.get("items").get(0).get("text").asText())
                .contains("kafka");
        assertThat(resp.get("items").get(0).get("source").asText())
                .startsWith("memory:");
        assertThat(resp.get("pack").asText())
                .startsWith("다음은 관련 메모리입니다:\n[1] kafka");
    }

    @Test
    void POST_context_다른_projectKey_데이터는_섞이지_않는다() throws Exception {
        repo.insert("proj-A", null, "kafka 메모 A프로젝트", vec(0), "MANUAL", "fake-001", 10);
        repo.insert("proj-B", null, "kafka 메모 B프로젝트", vec(0), "MANUAL", "fake-001", 10);

        String body = json.writeValueAsString(Map.of(
                "query", "kafka",
                "projectKey", "proj-A",
                "topK", 10
        ));

        MvcResult result = mvc.perform(post("/api/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode resp = json.readTree(result.getResponse().getContentAsString());
        // proj-A의 메모만 보여야 함 — 1건.
        assertThat(resp.get("items")).hasSize(1);
        assertThat(resp.get("items").get(0).get("text").asText()).contains("A프로젝트");
    }

    private static float[] vec(int oneHotIndex) {
        float[] v = new float[768];
        v[oneHotIndex] = 1.0f;
        return v;
    }
}
