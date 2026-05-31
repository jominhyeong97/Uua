package com.uua.context;

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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * ContextController가 PRD 상태코드 표대로 응답하는지 검증.
 * ContextService는 @MockitoBean으로 대체 — DB·임베딩 호출은 발생하지 않는다.
 */
@WebMvcTest(ContextController.class)
@Import(GlobalExceptionHandler.class)
class ContextControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean ContextService contextService;

    @Test
    void POST_context_정상_요청이면_200_및_pack_응답() throws Exception {
        ContextResponse fake = new ContextResponse(
                "다음은 관련 메모리입니다:\n[1] 카프카 보상 트랜잭션 결정",
                List.of(new ContextResponse.ContextItem(
                        "카프카 보상 트랜잭션 결정",
                        "memory:42",
                        Instant.parse("2026-05-30T09:00:00Z"),
                        0.91
                )),
                12,
                1000
        );
        given(contextService.buildPack(any())).willReturn(fake);

        String body = json.writeValueAsString(Map.of(
                "query", "어제 결정한 카프카",
                "projectKey", "uua",
                "maxTokens", 1000
        ));

        mvc.perform(post("/api/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pack").value(org.hamcrest.Matchers.startsWith("다음은 관련 메모리입니다:")))
                .andExpect(jsonPath("$.items[0].source").value("memory:42"))
                .andExpect(jsonPath("$.usedTokens").value(12))
                .andExpect(jsonPath("$.maxTokens").value(1000));
    }

    @Test
    void POST_context_빈_query면_400() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "query", "   ",
                "projectKey", "uua"
        ));

        mvc.perform(post("/api/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.field").value("query"));
    }

    @Test
    void POST_context_query가_8001자면_413() throws Exception {
        String tooLong = "a".repeat(8001);
        String body = json.writeValueAsString(Map.of(
                "query", tooLong,
                "projectKey", "uua"
        ));

        mvc.perform(post("/api/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("text_too_long"))
                .andExpect(jsonPath("$.max").value(8000));
    }

    @Test
    void POST_context_maxTokens가_8001이면_400() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "query", "ok",
                "projectKey", "uua",
                "maxTokens", 8001
        ));

        mvc.perform(post("/api/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.field").value("maxTokens"));
    }

    @Test
    void POST_context_EmbeddingException이면_503_cause_매핑() throws Exception {
        willThrow(new EmbeddingException(EmbeddingException.Reason.TIMEOUT, "timeout"))
                .given(contextService).buildPack(any());

        String body = json.writeValueAsString(Map.of(
                "query", "ok",
                "projectKey", "uua"
        ));

        mvc.perform(post("/api/context")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("embedding_unavailable"))
                .andExpect(jsonPath("$.cause").value("TIMEOUT"));
    }
}
