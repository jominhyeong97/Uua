package com.uua.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uua.common.GlobalExceptionHandler;
import com.uua.embedding.EmbeddingException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * IngestController가 상태코드 표대로 응답하는지 검증.
 * IngestService는 @MockitoBean으로 대체 — DB·임베딩 호출은 발생하지 않는다.
 *
 * MethodValidationPostProcessor를 명시 import — @Validated + @PathVariable의 @Size 위반을
 * ConstraintViolationException으로 던지게 하려면 필요(WebMvcTest 슬라이스에선 기본 등록 안 됨).
 */
@WebMvcTest(IngestController.class)
@Import({GlobalExceptionHandler.class, MethodValidationPostProcessor.class})
class IngestControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean IngestService ingestService;

    @Test
    void POST_ingest_정상이면_201_및_응답바디() throws Exception {
        given(ingestService.ingest(anyString(), any()))
                .willReturn(new IngestResponse("s-1", "uua", 2, List.of(101L, 102L), 1000));

        String body = json.writeValueAsString(Map.of(
                "text", "hello world",
                "projectKey", "uua"
        ));

        mvc.perform(post("/api/sessions/{sessionId}/ingest", "s-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.chunks").value(2))
                .andExpect(jsonPath("$.ids[0]").value(101))
                .andExpect(jsonPath("$.tokensTotal").value(1000));
    }

    @Test
    void POST_ingest_빈_text면_400() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "text", "   ",
                "projectKey", "uua"
        ));

        mvc.perform(post("/api/sessions/{sessionId}/ingest", "s-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.field").value("text"));
    }

    @Test
    void POST_ingest_text_200001자면_400_size위반() throws Exception {
        // text 상한 200000자. 단계②/③의 8000자처럼 길이 위반 시 413으로 묶지 않고,
        // 200KB는 명백한 잘못된 요청으로 보고 400(validation_failed)으로 응답.
        // (text/query 413 매핑은 8000자 본문 전용. ingest의 200KB는 다른 의미.)
        String tooLong = "a".repeat(200_001);
        String body = json.writeValueAsString(Map.of(
                "text", tooLong,
                "projectKey", "uua"
        ));

        mvc.perform(post("/api/sessions/{sessionId}/ingest", "s-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.field").value("text"));
    }

    @Test
    void POST_ingest_IngestPartialFailure면_503_committed_매핑() throws Exception {
        EmbeddingException cause = new EmbeddingException(
                EmbeddingException.Reason.RATE_LIMIT, "429");
        willThrow(new IngestPartialFailure(cause, 3, 4))
                .given(ingestService).ingest(anyString(), any());

        String body = json.writeValueAsString(Map.of(
                "text", "hello",
                "projectKey", "uua"
        ));

        mvc.perform(post("/api/sessions/{sessionId}/ingest", "s-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("embedding_unavailable"))
                .andExpect(jsonPath("$.cause").value("RATE_LIMIT"))
                .andExpect(jsonPath("$.committed").value(3))
                .andExpect(jsonPath("$.failedAt").value(4));
    }
}
