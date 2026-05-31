package com.uua.embedding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Gemini의 embedContent API를 호출해 텍스트를 float[768]로 바꾼다.
 *
 * 모든 실패(429/5xx/timeout/차원 mismatch/api-key 누락)는 단일 {@link EmbeddingException}으로 통일 —
 * 상위(MemoryService→Controller→GlobalExceptionHandler)는 그저 503으로 매핑하면 된다.
 *
 * 호출마다 INFO 한 줄: latency_ms + outcome enum. text 본문이나 api-key는 절대 로그에 찍지 않는다.
 *
 * <p>단계 ⑤부터 {@link EmbeddingClient} 인터페이스를 더 이상 직접 구현하지 않는다 —
 * {@link UsageGuardedEmbeddingClient}가 이 클래스를 감싸 킬스위치/일일상한/usage_log를 더한 뒤
 * 인터페이스를 노출한다. 호출자 서비스들은 그 데코레이터를 주입받는다.
 */
@Component
public class GeminiEmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingClient.class);

    private final EmbeddingProperties props;
    private final RestClient http;

    public GeminiEmbeddingClient(EmbeddingProperties props, RestClient.Builder builder) {
        this.props = props;
        // RestClient에 connect/read 타임아웃을 직접 박는다 — Render 콜드스타트 영향이 없는 Google API 콜이므로 10s면 충분.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration t = Duration.ofSeconds(props.timeoutSeconds());
        factory.setConnectTimeout(t);
        factory.setReadTimeout(t);
        this.http = builder
                .baseUrl(props.baseUrl())
                .requestFactory(factory)
                .build();
    }

    public float[] embed(String text) {
        // 1) api-key 미설정은 외부 호출 전에 차단 — 무료티어 키가 없는 상태에서 그래도 요청이 가는 걸 막는다.
        if (props.apiKey() == null || props.apiKey().isBlank()) {
            log.info("gemini.embed outcome=API_KEY_MISSING");
            throw new EmbeddingException(EmbeddingException.Reason.API_KEY_MISSING,
                    "GEMINI_API_KEY is not configured");
        }

        long start = System.nanoTime();
        try {
            // Gemini embedContent 요청 — outputDimensionality로 768 고정.
            Map<String, Object> body = Map.of(
                    "content", Map.of("parts", List.of(Map.of("text", text))),
                    "outputDimensionality", props.dimension()
            );

            EmbedResponse resp = http.post()
                    .uri("/v1beta/models/{model}:embedContent", props.model())
                    .header("x-goog-api-key", props.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    // 4xx/5xx는 우리가 enum으로 분류 — 기본 예외변환을 끄고 직접 처리.
                    .onStatus(HttpStatusCode::isError, (req, res) -> {
                        HttpStatusCode code = res.getStatusCode();
                        if (code.value() == 429) {
                            throw new EmbeddingException(EmbeddingException.Reason.RATE_LIMIT,
                                    "gemini 429 too many requests");
                        }
                        if (code.is5xxServerError()) {
                            throw new EmbeddingException(EmbeddingException.Reason.SERVER_ERROR,
                                    "gemini server error " + code.value());
                        }
                        // 4xx(429 제외)는 호출자 잘못이지만 v1엔 더 세분화하지 않는다 — INVALID_RESPONSE로 묶음.
                        throw new EmbeddingException(EmbeddingException.Reason.INVALID_RESPONSE,
                                "gemini error status " + code.value());
                    })
                    .body(EmbedResponse.class);

            float[] vec = toFloatArray(resp);
            log.info("gemini.embed outcome=SUCCESS latency_ms={} dim={}", elapsedMs(start), vec.length);
            return vec;

        } catch (EmbeddingException e) {
            log.info("gemini.embed outcome={} latency_ms={}", e.reason(), elapsedMs(start));
            throw e;
        } catch (ResourceAccessException e) {
            // RestClient는 connect/read timeout을 ResourceAccessException으로 감싸는 게 정석이지만,
            // body 수신 중 timeout 등 일부 경로는 HttpMessageConversionException 등 다른 타입으로 떨어진다.
            // 원인 체인을 walk해서 timeout 여부를 판정한다.
            EmbeddingException.Reason r = isTimeoutCause(e)
                    ? EmbeddingException.Reason.TIMEOUT
                    : EmbeddingException.Reason.SERVER_ERROR;
            log.info("gemini.embed outcome={} latency_ms={}", r, elapsedMs(start));
            throw new EmbeddingException(r, "gemini network failure: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            // ResourceAccessException으로 감싸이지 않은 IO/timeout/파싱 실패도 여기로 떨어질 수 있음.
            // body delay로 인한 read timeout이 대표 사례.
            EmbeddingException.Reason r = isTimeoutCause(e)
                    ? EmbeddingException.Reason.TIMEOUT
                    : EmbeddingException.Reason.INVALID_RESPONSE;
            log.info("gemini.embed outcome={} latency_ms={}", r, elapsedMs(start));
            throw new EmbeddingException(r,
                    "gemini response failure: " + e.getMessage(), e);
        }
    }

    /** SocketTimeoutException 또는 그 부모(InterruptedIOException)가 원인 체인에 있으면 timeout으로 본다. */
    private static boolean isTimeoutCause(Throwable t) {
        for (Throwable cur = t; cur != null; cur = cur.getCause()) {
            if (cur instanceof java.io.InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    private float[] toFloatArray(EmbedResponse resp) {
        if (resp == null || resp.embedding() == null || resp.embedding().values() == null) {
            throw new EmbeddingException(EmbeddingException.Reason.INVALID_RESPONSE,
                    "gemini response missing embedding.values");
        }
        List<Double> values = resp.embedding().values();
        if (values.size() != props.dimension()) {
            throw new EmbeddingException(EmbeddingException.Reason.INVALID_RESPONSE,
                    "gemini returned dim=" + values.size() + " (expected " + props.dimension() + ")");
        }
        float[] out = new float[values.size()];
        for (int i = 0; i < values.size(); i++) {
            out[i] = values.get(i).floatValue();
        }
        return out;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** Gemini embedContent 응답 구조: { "embedding": { "values": [..] } } */
    public record EmbedResponse(Embedding embedding) {
        public record Embedding(List<Double> values) {}
    }
}
