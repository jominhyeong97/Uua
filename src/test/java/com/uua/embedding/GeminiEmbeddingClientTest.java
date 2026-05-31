package com.uua.embedding;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * GeminiEmbeddingClient의 6가지 외부 응답 시나리오를 가짜 HTTP 서버로 검증한다.
 *
 * 통합 테스트가 아니라 단위 테스트 — Spring 컨텍스트를 띄우지 않고 클라이언트만 직접 인스턴스화한다.
 * 실제 Gemini는 호출하지 않는다(무료 RPM 한도 + Google 학습 사용 우려).
 */
class GeminiEmbeddingClientTest {

    private MockWebServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    /** 테스트마다 base-url을 MockWebServer로 바꾸기 위해 클라이언트를 직접 만든다. */
    private GeminiEmbeddingClient newClient(int timeoutSeconds) {
        EmbeddingProperties props = new EmbeddingProperties(
                "test-key",
                server.url("/").toString().replaceAll("/$", ""),
                "gemini-embedding-001",
                timeoutSeconds,
                768
        );
        return new GeminiEmbeddingClient(props, RestClient.builder());
    }

    @Test
    void embed_정상_768차원_응답을_float배열로_반환한다() {
        // 768개의 0.0 값으로 채운 JSON 응답
        String values = IntStream.range(0, 768).mapToObj(i -> "0.0").reduce((a, b) -> a + "," + b).orElseThrow();
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"embedding\":{\"values\":[" + values + "]}}"));

        float[] out = newClient(10).embed("hello");

        assertThat(out).hasSize(768);
    }

    @Test
    void embed_429응답이면_RATE_LIMIT_예외() {
        server.enqueue(new MockResponse().setResponseCode(429).setBody("{\"error\":\"rate\"}"));

        assertThatThrownBy(() -> newClient(10).embed("hello"))
                .isInstanceOf(EmbeddingException.class)
                .satisfies(e -> assertThat(((EmbeddingException) e).reason())
                        .isEqualTo(EmbeddingException.Reason.RATE_LIMIT));
    }

    @Test
    void embed_5xx응답이면_SERVER_ERROR_예외() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{\"error\":\"unavailable\"}"));

        assertThatThrownBy(() -> newClient(10).embed("hello"))
                .isInstanceOf(EmbeddingException.class)
                .satisfies(e -> assertThat(((EmbeddingException) e).reason())
                        .isEqualTo(EmbeddingException.Reason.SERVER_ERROR));
    }

    @Test
    void embed_응답이_지연되면_TIMEOUT_예외() {
        // timeout 1s, 응답 지연 3s → SocketTimeoutException → TIMEOUT
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setBody("{\"embedding\":{\"values\":[0]}}")
                .setBodyDelay(3, TimeUnit.SECONDS));

        assertThatThrownBy(() -> newClient(1).embed("hello"))
                .isInstanceOf(EmbeddingException.class)
                .satisfies(e -> assertThat(((EmbeddingException) e).reason())
                        .isEqualTo(EmbeddingException.Reason.TIMEOUT));
    }

    @Test
    void embed_차원이_다르면_INVALID_RESPONSE_예외() {
        // 응답은 200이지만 길이가 3 → DB의 vector(768)에 못 넣음
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("{\"embedding\":{\"values\":[0.1,0.2,0.3]}}"));

        assertThatThrownBy(() -> newClient(10).embed("hello"))
                .isInstanceOf(EmbeddingException.class)
                .satisfies(e -> assertThat(((EmbeddingException) e).reason())
                        .isEqualTo(EmbeddingException.Reason.INVALID_RESPONSE));
    }

    @Test
    void embed_api_key가_비어있으면_외부호출없이_API_KEY_MISSING() {
        EmbeddingProperties noKey = new EmbeddingProperties(
                "",
                server.url("/").toString(),
                "gemini-embedding-001",
                10,
                768
        );
        GeminiEmbeddingClient client = new GeminiEmbeddingClient(noKey, RestClient.builder());

        assertThatThrownBy(() -> client.embed("hello"))
                .isInstanceOf(EmbeddingException.class)
                .satisfies(e -> assertThat(((EmbeddingException) e).reason())
                        .isEqualTo(EmbeddingException.Reason.API_KEY_MISSING));

        // 외부 호출이 아예 발생하지 않았는지 확인 — MockWebServer 큐 사용 0
        assertThat(server.getRequestCount()).isZero();
    }
}
