package com.uua.memory;

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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MemoryController가 PRD §4의 상태코드 표대로 응답하는지 검증.
 * MemoryService는 @MockitoBean으로 대체 — DB·임베딩 호출은 발생하지 않는다.
 */
@WebMvcTest(MemoryController.class)
@Import(GlobalExceptionHandler.class)
class MemoryControllerTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @MockitoBean MemoryService memoryService;

    @Test
    void POST_memories_정상_요청이면_201_및_응답_바디() throws Exception {
        given(memoryService.create(any())).willReturn(
                new CreateMemoryResponse(42L, "uua", Instant.parse("2026-05-31T01:23:45Z"), 5));

        String body = json.writeValueAsString(Map.of(
                "text", "hello world",
                "projectKey", "uua"
        ));

        mvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", org.hamcrest.Matchers.endsWith("/api/memories/42")))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.projectKey").value("uua"))
                .andExpect(jsonPath("$.tokenCount").value(5));
    }

    @Test
    void POST_memories_빈_text면_400() throws Exception {
        String body = json.writeValueAsString(Map.of(
                "text", "   ",
                "projectKey", "uua"
        ));

        mvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_failed"))
                .andExpect(jsonPath("$.field").value("text"));
    }

    @Test
    void POST_memories_text가_8001자면_413() throws Exception {
        String tooLong = "a".repeat(8001);
        String body = json.writeValueAsString(Map.of(
                "text", tooLong,
                "projectKey", "uua"
        ));

        mvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("text_too_long"))
                .andExpect(jsonPath("$.max").value(8000));
    }

    @Test
    void POST_memories_EmbeddingException이면_503_cause_매핑() throws Exception {
        willThrow(new EmbeddingException(EmbeddingException.Reason.RATE_LIMIT, "429"))
                .given(memoryService).create(any());

        String body = json.writeValueAsString(Map.of(
                "text", "hello",
                "projectKey", "uua"
        ));

        mvc.perform(post("/api/memories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("embedding_unavailable"))
                .andExpect(jsonPath("$.cause").value("RATE_LIMIT"));
    }
}
